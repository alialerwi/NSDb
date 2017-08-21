package io.radicalbit.nsdb.actors

import java.nio.file.Paths

import akka.actor.{Actor, ActorLogging, Props}
import io.radicalbit.nsdb.actors.NamespaceDataActor.commands._
import io.radicalbit.nsdb.actors.NamespaceDataActor.events._
import io.radicalbit.nsdb.coordinator.ReadCoordinator
import io.radicalbit.nsdb.index.TimeSeriesIndex
import io.radicalbit.nsdb.statement.StatementParser
import org.apache.lucene.index.IndexNotFoundException
import org.apache.lucene.store.FSDirectory

import scala.util.{Failure, Success, Try}

class IndexerActor(basePath: String, namespace: String) extends Actor with ActorLogging {
  import scala.collection.mutable

  private val statementParser = new StatementParser()

  private val indexes: mutable.Map[String, TimeSeriesIndex] = mutable.Map.empty

  private def getIndex(metric: String) =
    indexes.getOrElse(metric, {
      val path     = FSDirectory.open(Paths.get(basePath, namespace, metric))
      val newIndex = new TimeSeriesIndex(path)
      indexes += (metric -> newIndex)
      newIndex
    })

  override def receive: Receive = {
    case AddRecord(ns, metric, record) =>
      val index           = getIndex(metric)
      implicit val writer = index.getWriter
      index.write(record)
      writer.flush()
      writer.close()
      sender ! RecordAdded(ns, metric, record)
    case AddRecords(ns, metric, records) =>
      val index           = getIndex(metric)
      implicit val writer = index.getWriter
      records.foreach(index.write)
      writer.flush()
      writer.close()
      sender ! RecordsAdded(ns, metric, records)
    case DeleteRecord(ns, metric, record) =>
      val index           = getIndex(metric)
      implicit val writer = index.getWriter
      index.delete(record)
      writer.flush()
      writer.close()
      sender ! RecordDeleted(ns, metric, record)
    case DeleteMetric(ns, metric) =>
      val index           = getIndex(metric)
      implicit val writer = index.getWriter
      index.deleteAll()
      writer.close()
      sender ! MetricDeleted(ns, metric)
    case DeleteAllMetrics(ns) =>
      indexes.foreach {
        case (_, index) =>
          implicit val writer = index.getWriter
          index.deleteAll()
          writer.close()
      }
      sender ! AllMetricsDeleted(ns)
    case GetCount(ns, metric) =>
      val index = getIndex(metric)
      val hits  = index.timeRange(0, Long.MaxValue)
      sender ! CountGot(ns, metric, hits.size)
    case ReadCoordinator.ExecuteSelectStatement(statement, schema) =>
      val queryResult = statementParser.parseStatement(statement, schema).get
      Try { getIndex(statement.metric).query(queryResult.q, queryResult.limit, queryResult.sort) } match {
        case Success(docs) =>
          log.debug("found {} records", docs.size)
          sender() ! ReadCoordinator.SelectStatementExecuted(docs)
        case Failure(_: IndexNotFoundException) =>
          log.debug("index not found")
          sender() ! ReadCoordinator.SelectStatementExecuted(Seq.empty)
        case Failure(ex) =>
          ex.printStackTrace()
          println("select statement failed ")
          log.error(ex, "select statement failed")
          sender() ! ReadCoordinator.SelectStatementFailed(ex.getMessage)
      }
  }
}

object IndexerActor {

  def props(basePath: String, namespace: String): Props = Props(new IndexerActor(basePath, namespace: String))

}