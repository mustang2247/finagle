package com.twitter.finagle.thrift

import com.twitter.app.GlobalFlag
import com.twitter.conversions.storage._
import com.twitter.finagle.stats.{Counter, NullStatsReceiver, StatsReceiver}
import com.twitter.finagle._
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.service.{ReqRep, ResponseClass, ResponseClassifier}
import com.twitter.scrooge._
import com.twitter.util._
import java.util.Arrays
import org.apache.thrift.TApplicationException
import org.apache.thrift.protocol.{TMessage, TMessageType, TProtocolFactory}
import org.apache.thrift.transport.TMemoryInputTransport

object maxReusableBufferSize
    extends GlobalFlag[StorageUnit](
      16.kilobytes,
      "Max size (bytes) for ThriftServiceIface reusable transport buffer"
    )

/**
 * Typeclass ServiceIfaceBuilder[T] creates T-typed interfaces from thrift clients.
 * Scrooge generates implementations of this builder.
 */
trait ServiceIfaceBuilder[ServiceIface <: ThriftServiceIface.Filterable[ServiceIface]] {

  /**
   * Build a client ServiceIface wrapping a binary thrift service.
   *
   * @param thriftService An underlying thrift service that works on byte arrays.
   * @param clientParam RichClientParam wraps client params [[com.twitter.finagle.thrift.RichClientParam]].
   */
  def newServiceIface(
    thriftService: Service[ThriftClientRequest, Array[Byte]],
    clientParam: RichClientParam
  ): ServiceIface

  @deprecated("Use com.twitter.finagle.thrift.RichClientParam", "2017-08-16")
  def newServiceIface(
    thriftService: Service[ThriftClientRequest, Array[Byte]],
    pf: TProtocolFactory = Protocols.binaryFactory(),
    stats: StatsReceiver = NullStatsReceiver,
    responseClassifier: ResponseClassifier = ResponseClassifier.Default
  ): ServiceIface = {
    val clientParam =
      RichClientParam(pf, clientStats = stats, responseClassifier = responseClassifier)
    newServiceIface(thriftService, clientParam)
  }

  @deprecated("Use com.twitter.finagle.thrift.RichClientParam", "2017-08-16")
  def newServiceIface(
    thriftService: Service[ThriftClientRequest, Array[Byte]],
    pf: TProtocolFactory,
    stats: StatsReceiver
  ): ServiceIface = {
    val clientParam = RichClientParam(pf, clientStats = stats)
    newServiceIface(thriftService, clientParam)
  }
}

/**
 * A typeclass to construct a MethodIface by wrapping a ServiceIface.
 * This is a compatibility constructor to replace an existing Future interface
 * with one built from a ServiceIface.
 *
 * Scrooge generates implementations of this builder.
 */
trait MethodIfaceBuilder[ServiceIface, MethodIface] {

  /**
   * Build a FutureIface wrapping a ServiceIface.
   */
  def newMethodIface(serviceIface: ServiceIface): MethodIface
}

object ThriftMethodStats {
  def apply(stats: StatsReceiver): ThriftMethodStats =
    ThriftMethodStats(
      stats.counter("requests"),
      stats.counter("success"),
      stats.counter("failures"),
      stats.scope("failures")
    )
}

case class ThriftMethodStats(
  requestsCounter: Counter,
  successCounter: Counter,
  failuresCounter: Counter,
  failuresScope: StatsReceiver
)

/**
 * Construct Service interface for a Thrift method.
 *
 * There are two ways to use a Scrooge-generated Thrift `Service` with Finagle:
 *
 * 1. Using a Service interface, i.e. a collection of Finagle `Services`.
 *
 * 2. Using a method interface, i.e. a collection of methods returning `Futures`.
 *
 * Example: for a Thrift service IDL:
 * {{{
 * service Logger {
 *   string log(1: string message, 2: i32 logLevel);
 *   i32 getLogSize();
 * }
 * }}}
 *
 * the `Service` interface, or `ServiceIface`, is
 * {{{
 * trait LoggerServiceIface {
 *   val log: com.twitter.finagle.Service[Logger.Log.Args, Logger.Log.SuccessType]
 *   val getLogSize: com.twitter.finagle.Service[Logger.GetLogSize.Args, Logger.GetLogSize.SuccessType]
 * }
 * }}}
 *
 * and the method interface, or `MethodIface`, is
 * {{{
 * trait Logger[Future] {
 *   def log(message: String, logLevel: Int): Future[String]
 *   def getLogSize(): Future[Int]
 * }
 * }}}
 *
 * Service interfaces can be modified and composed with Finagle `Filters`.
 */
object ThriftServiceIface {

  /**
   * Build a Service from a given Thrift method.
   */
  def apply(
    method: ThriftMethod,
    thriftService: Service[ThriftClientRequest, Array[Byte]],
    clientParam: RichClientParam
  ): Service[method.Args, method.SuccessType] =
    statsFilter(method, clientParam.clientStats, clientParam.responseClassifier)
      .andThen(thriftCodecFilter(method, clientParam.protocolFactory))
      .andThen(thriftService)

  @deprecated("Use com.twitter.finagle.thrift.RichClientParam", "2017-08-16")
  def apply(
    method: ThriftMethod,
    thriftService: Service[ThriftClientRequest, Array[Byte]],
    pf: TProtocolFactory,
    stats: StatsReceiver,
    responseClassifier: ResponseClassifier
  ): Service[method.Args, method.SuccessType] = {
    apply(method, thriftService, RichClientParam(pf, clientStats = stats, responseClassifier = responseClassifier))
  }

  def apply(
    method: ThriftMethod,
    thriftService: Service[ThriftClientRequest, Array[Byte]],
    pf: TProtocolFactory,
    stats: StatsReceiver
  ): Service[method.Args, method.SuccessType] =
    apply(method, thriftService, RichClientParam(pf, clientStats = stats))

  /**
   * Used in conjunction with [[ServiceIfaceBuilder]] to allow for filtering
   * of a `ServiceIface`.
   */
  trait Filterable[T] {

    /**
     * Prepend the given type-agnostic [[Filter]].
     */
    def filtered(filter: Filter.TypeAgnostic): T
  }

  /**
   * A [[Filter]] that updates success and failure stats for a thrift method.
   * Responses are classified according to `responseClassifier`.
   */
  private def statsFilter(
    method: ThriftMethod,
    stats: StatsReceiver,
    responseClassifier: ResponseClassifier
  ): SimpleFilter[method.Args, method.SuccessType] = {
    val methodStats = ThriftMethodStats(stats.scope(method.serviceName).scope(method.name))
    new SimpleFilter[method.Args, method.SuccessType] {
      def apply(
        args: method.Args,
        service: Service[method.Args, method.SuccessType]
      ): Future[method.SuccessType] = {
        methodStats.requestsCounter.incr()
        service(args).respond { response =>
          val responseClass =
            responseClassifier.applyOrElse(ReqRep(args, response), ResponseClassifier.Default)
          responseClass match {
            case ResponseClass.Successful(_) =>
              methodStats.successCounter.incr()
            case ResponseClass.Failed(_) =>
              methodStats.failuresCounter.incr()
              response match {
                case Throw(ex) =>
                  methodStats.failuresScope.counter(Throwables.mkString(ex): _*).incr()
                case _ =>
              }
          }
        }
      }
    }
  }

  /**
   * A [[Filter]] that wraps a binary thrift Service[ThriftClientRequest, Array[Byte]]
   * and produces a [[Service]] from a [[ThriftStruct]] to [[ThriftClientRequest]] (i.e. bytes).
   */
  private def thriftCodecFilter(
    method: ThriftMethod,
    pf: TProtocolFactory
  ): Filter[method.Args, method.SuccessType, ThriftClientRequest, Array[Byte]] =
    new Filter[method.Args, method.SuccessType, ThriftClientRequest, Array[Byte]] {
      private[this] val decodeRepFn: Array[Byte] => Try[method.SuccessType] = { bytes =>
        val result: method.Result = decodeResponse(bytes, method.responseCodec, pf)
        result.successField match {
          case Some(v) => Return(v)
          case None =>
            result.firstException() match {
              case Some(ex) => Throw(ex)
              case None =>
                Throw(
                  new TApplicationException(
                    TApplicationException.MISSING_RESULT,
                    s"Thrift method '${method.name}' failed: missing result"
                  )
                )
            }
        }
      }

      def apply(
        args: method.Args,
        service: Service[ThriftClientRequest, Array[Byte]]
      ): Future[method.SuccessType] = {
        val request = encodeRequest(method.name, args, pf, method.oneway)
        val serdeCtx = new DeserializeCtx[method.SuccessType](args, decodeRepFn)
        Contexts.local.let(DeserializeCtx.Key, serdeCtx) {
          service(request).flatMap { response =>
            Future.const(serdeCtx.deserialize(response))
          }
        }
      }
    }

  def resultFilter(
    method: ThriftMethod
  ): Filter[method.Args, method.SuccessType, method.Args, method.Result] =
    new Filter[method.Args, method.SuccessType, method.Args, method.Result] {
      private[this] val responseFn: method.Result => Future[method.SuccessType] = { response =>
        response.firstException() match {
          case Some(exception) =>
            setServiceName(exception, method.serviceName)
            Future.exception(exception)
          case None =>
            response.successField match {
              case Some(result) =>
                Future.value(result)
              case None =>
                Future.exception(
                  new TApplicationException(
                    TApplicationException.MISSING_RESULT,
                    s"Thrift method '${method.name}' failed: missing result"
                  )
                )
            }
        }
      }

      def apply(
        args: method.Args,
        service: Service[method.Args, method.Result]
      ): Future[method.SuccessType] =
        service(args).flatMap(responseFn)
    }

  private[this] val tlReusableBuffer = TReusableBuffer(
    maxThriftBufferSize = maxReusableBufferSize().inBytes.toInt
  )

  private def encodeRequest(
    methodName: String,
    args: ThriftStruct,
    pf: TProtocolFactory,
    oneway: Boolean
  ): ThriftClientRequest = {
    val buf = tlReusableBuffer.get()
    val oprot = pf.getProtocol(buf)

    oprot.writeMessageBegin(new TMessage(methodName, TMessageType.CALL, 0))
    args.write(oprot)
    oprot.writeMessageEnd()

    val bytes = Arrays.copyOfRange(buf.getArray(), 0, buf.length())
    tlReusableBuffer.reset()

    new ThriftClientRequest(bytes, oneway)
  }

  private def decodeResponse[T <: ThriftStruct](
    resBytes: Array[Byte],
    codec: ThriftStructCodec[T],
    pf: TProtocolFactory
  ): T = {
    val iprot = pf.getProtocol(new TMemoryInputTransport(resBytes))
    val msg = iprot.readMessageBegin()
    if (msg.`type` == TMessageType.EXCEPTION) {
      val exception = TApplicationException.read(iprot)
      iprot.readMessageEnd()
      throw exception
    } else {
      val result = codec.decode(iprot)
      iprot.readMessageEnd()
      result
    }
  }

  private def setServiceName(ex: Throwable, serviceName: String): Throwable =
    ex match {
      case se: SourcedException if !serviceName.isEmpty =>
        se.serviceName = serviceName
        se
      case _ => ex
    }
}
