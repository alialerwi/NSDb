package io.radicalbit.nsdb.coordinator

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import io.radicalbit.commit_log.CommitLogService.{Delete, Insert}
import io.radicalbit.nsdb.actors.NamespaceDataActor.events.RecordRejected
import io.radicalbit.nsdb.actors.PublisherActor.Command.SubscribeBySqlStatement
import io.radicalbit.nsdb.actors.PublisherActor.Events.{RecordPublished, Subscribed}
import io.radicalbit.nsdb.actors._
import io.radicalbit.nsdb.commit_log.CommitLogWriterActor.WroteToCommitLogAck
import io.radicalbit.nsdb.coordinator.WriteCoordinator.{DeleteNamespace, InputMapped, MapInput, NamespaceDeleted}
import io.radicalbit.nsdb.model.Record
import io.radicalbit.nsdb.statement._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

class TestCommitLogService extends Actor {
  def receive = {
    case Insert(ts, metric, record) =>
      sender() ! WroteToCommitLogAck(ts, metric, record)
    case Delete(_, _) => sys.error("Not Implemented")
  }
}

class TestSubscriber extends Actor {
  var receivedMessages = 0
  def receive = {
    case RecordPublished(_, _) =>
      receivedMessages += 1
  }
}

class WriteCoordinatorSpec
    extends TestKit(ActorSystem("nsdb-test"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  val probe                = TestProbe()
  val probeActor           = probe.ref
  val namespaceSchemaActor = TestActorRef[NamespaceSchemaActor](NamespaceSchemaActor.props("target/test_index"))
  val namespaceDataActor   = TestActorRef[NamespaceDataActor](NamespaceDataActor.props("target/test_index"))
  val subscriber           = TestActorRef[TestSubscriber](Props[TestSubscriber])
  val publisherActor       = TestActorRef[PublisherActor](PublisherActor.props("target/test_index"))
  val writeCoordinatorActor = system actorOf WriteCoordinator.props(namespaceSchemaActor,
                                                                    system.actorOf(Props[TestCommitLogService]),
                                                                    namespaceDataActor,
                                                                    publisherActor)

  "WriteCoordinator" should "write records" in {
    val record1 = Record(System.currentTimeMillis, Map("content" -> s"content"), Map.empty)
    val record2 = Record(System.currentTimeMillis, Map("content" -> s"content", "content2" -> s"content2"), Map.empty)
    val incompatibleRecord =
      Record(System.currentTimeMillis, Map("content" -> 1, "content2" -> s"content2"), Map.empty)

    probe.send(writeCoordinatorActor, MapInput(System.currentTimeMillis, "testNamespace", "testMetric", record1))

    val expectedAdd = probe.expectMsgType[InputMapped]
    expectedAdd.metric shouldBe "testMetric"
    expectedAdd.record shouldBe record1

    probe.send(writeCoordinatorActor, MapInput(System.currentTimeMillis, "testNamespace", "testMetric", record2))

    val expectedAdd2 = probe.expectMsgType[InputMapped]
    expectedAdd2.metric shouldBe "testMetric"
    expectedAdd2.record shouldBe record2

    probe.send(writeCoordinatorActor,
               MapInput(System.currentTimeMillis, "testNamespace", "testMetric", incompatibleRecord))

    probe.expectMsgType[RecordRejected]

  }

  "WriteCoordinator" should "write records and publish event to its subscriber" in {
    val testRecordSatisfy = Record(100, Map("name" -> "john"), Map.empty)

    val testSqlStatement = SelectSQLStatement(
      namespace = "registry",
      metric = "testMetric",
      fields = AllFields,
      condition = Some(
        Condition(ComparisonExpression(dimension = "timestamp", comparison = GreaterOrEqualToOperator, value = 10L))),
      limit = Some(LimitOperator(4))
    )

    probe.send(publisherActor, SubscribeBySqlStatement(subscriber, testSqlStatement))
    probe.expectMsgType[Subscribed]
    publisherActor.underlyingActor.subscribedActors.keys.size shouldBe 1
    publisherActor.underlyingActor.queries.keys.size shouldBe 1

    probe.send(writeCoordinatorActor,
               MapInput(System.currentTimeMillis, "testNamespace", "testMetric", testRecordSatisfy))

    val expectedAdd = probe.expectMsgType[InputMapped]
    expectedAdd.metric shouldBe "testMetric"
    expectedAdd.record shouldBe testRecordSatisfy

    subscriber.underlyingActor.receivedMessages shouldBe 1
  }

  "WriteCoordinator" should "delete a nanespace" in {
    probe.send(writeCoordinatorActor, DeleteNamespace("testNamespace"))
    probe.expectMsgType[NamespaceDeleted]

    namespaceDataActor.underlyingActor.indexerActors.keys.size shouldBe 0
    namespaceSchemaActor.underlyingActor.schemaActors.keys.size shouldBe 0
  }

}