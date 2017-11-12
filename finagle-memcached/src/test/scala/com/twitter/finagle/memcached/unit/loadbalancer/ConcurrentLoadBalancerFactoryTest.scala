package com.twitter.finagle.memcached.unit.loadbalancer

import com.twitter.conversions.time._
import com.twitter.finagle.client.{StackClient, StringClient}
import com.twitter.finagle.loadbalancer.LoadBalancerFactory
import com.twitter.finagle.memcached.loadbalancer.ConcurrentLoadBalancerFactory
import com.twitter.finagle.server.StringServer
import com.twitter.finagle.{Address, Name, Service, Stack}
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.util.{Await, Future}
import java.net.{InetAddress, InetSocketAddress}
import org.scalatest.FunSuite

class ConcurrentLoadBalancerFactoryTest extends FunSuite {
  val echoService = Service.mk[String, String](Future.value(_))

  test("Default num connections param is 4") {
    val ConcurrentLoadBalancerFactory.Param(numConnections) =
      Stack.Params.empty[ConcurrentLoadBalancerFactory.Param]
    assert(numConnections == 4)
  }

  test("makes service factory stack") {
    val address = new InetSocketAddress(InetAddress.getLoopbackAddress, 0)
    val server = StringServer.server.serve(address, echoService)

    val sr = new InMemoryStatsReceiver
    val clientStack =
      StackClient.newStack
        .replace(LoadBalancerFactory.role, ConcurrentLoadBalancerFactory.module[String, String])
    val client = StringClient.client
      .withStack(clientStack)
      .withStatsReceiver(sr)
      .newService(
        Name.bound(Address(server.boundAddress.asInstanceOf[InetSocketAddress])),
        "client"
      )

    assert(sr.counters(Seq("client", "loadbalancer", "adds")) == 4)
    assert(Await.result(client("hello\n"), 15.seconds) == "hello")
  }

  test("creates fixed number of service factories based on params") {
    val addr1 = new InetSocketAddress(InetAddress.getLoopbackAddress, 0)
    val server1 = StringServer.server.serve(addr1, echoService)

    val addr2 = new InetSocketAddress(InetAddress.getLoopbackAddress, 0)
    val server2 = StringServer.server.serve(addr2, echoService)

    val sr = new InMemoryStatsReceiver
    val clientStack =
      StackClient.newStack
        .replace(LoadBalancerFactory.role, ConcurrentLoadBalancerFactory.module[String, String])

    val dest = Name.bound(
      Address(server1.boundAddress.asInstanceOf[InetSocketAddress]),
      Address(server2.boundAddress.asInstanceOf[InetSocketAddress])
    )

    val client = StringClient.client
      .withStack(clientStack)
      .withStatsReceiver(sr)
      .configured(ConcurrentLoadBalancerFactory.Param(3))
      .newService(dest, "client")

    assert(sr.counters(Seq("client", "loadbalancer", "adds")) == 6)
  }
}
