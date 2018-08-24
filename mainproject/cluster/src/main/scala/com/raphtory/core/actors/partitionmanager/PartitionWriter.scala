package com.raphtory.core.actors.partitionmanager

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorRef
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import com.raphtory.core.storage.EntitiesStorage
import com.raphtory.core.model.communication._
import com.raphtory.core.actors.RaphtoryActor
import com.raphtory.core.model.graphentities.Entity
import kamon.Kamon
import kamon.metric.GaugeMetric
import monix.eval.Task
import monix.execution.ExecutionModel.AlwaysAsyncExecution
import monix.execution.{ExecutionModel, Scheduler}

import scala.collection.concurrent.TrieMap
import scala.collection.parallel.mutable.ParTrieMap
import scala.concurrent.duration._

/**
  * The graph partition manages a set of vertices and there edges
  * Is sent commands which have been processed by the command Processor
  * Will process these, storing information in graph entities which may be updated if they already exist
  * */
class PartitionWriter(id : Int, test : Boolean, managerCountVal : Int) extends RaphtoryActor {
  var managerCount : Int = managerCountVal
  val managerID    : Int = id                   //ID which refers to the partitions position in the graph manager map

  val printing: Boolean = false                  // should the handled messages be printed to terminal
  val kLogging: Boolean = System.getenv().getOrDefault("PROMETHEUS", "true").trim().toBoolean // should the state of the vertex/edge map be output to Kamon/Prometheus
  val stdoutLog:Boolean = System.getenv().getOrDefault("STDOUT_LOG", "true").trim().toBoolean // A slower logging for the state of vertices/edges maps to Stdout

  val messageCount          : AtomicInteger = new AtomicInteger(0)         // number of messages processed since last report to the benchmarker
  val secondaryMessageCount : AtomicInteger = new AtomicInteger(0)


  val mediator : ActorRef   = DistributedPubSub(context.system).mediator // get the mediator for sending cluster messages

  val storage = EntitiesStorage.apply(printing, managerCount, managerID, mediator)

  mediator ! DistributedPubSubMediator.Put(self)

  val verticesGauge : GaugeMetric = Kamon.gauge("raphtory.vertices")
  val edgesGauge    : GaugeMetric = Kamon.gauge("raphtory.edges")


  //implicit val s : Scheduler = Scheduler(ExecutionModel.BatchedExecution(1024))

  // Explicit execution model
  implicit val s = Scheduler.io(
    name="my-io",
    executionModel = AlwaysAsyncExecution
  )
  // Simple constructor
  //implicit val s =
  //  Scheduler.computation(parallelism=16)

  /**
    * Set up partition to report how many messages it has processed in the last X seconds
    */
  override def preStart() {
    println("starting writer")
    context.system.scheduler.schedule(Duration(7, SECONDS),
      Duration(1, SECONDS), self, "tick")
    //context.system.scheduler.schedule(Duration(13, SECONDS),
    //    Duration(30, MINUTES), self, "profile")
    context.system.scheduler.schedule(Duration(1, SECONDS),
        Duration(1, MINUTES), self, "stdoutReport")
    context.system.scheduler.schedule(Duration(8, SECONDS),
      Duration(10, SECONDS), self, "keep_alive")
  }


  override def receive : Receive = {
    case "tick"       => reportIntake()
    case "profile"    => profile()
    case "keep_alive" => keepAlive()
    case "stdoutReport"=> Task.eval(reportStdout()).fork.runAsync

    //case LiveAnalysis(name,analyser) => mediator ! DistributedPubSubMediator.Send(name, Results(analyser.analyse(vertices,edges)), false)

    case VertexAdd(routerID,msgTime,srcId)                                => Task.eval(storage.vertexAdd(routerID,msgTime,srcId)).fork.runAsync.onComplete(_ => vHandle(srcId))
    case VertexRemoval(routerID,msgTime,srcId)                            => Task.eval(storage.vertexRemoval(routerID,msgTime,srcId)).fork.runAsync.onComplete(_ => vHandle(srcId))
    case VertexAddWithProperties(routerID,msgTime,srcId,properties)       => Task.eval(storage.vertexAdd(routerID,msgTime,srcId,properties)).fork.runAsync.onComplete(_ => vHandle(srcId))

    case EdgeAdd(routerID,msgTime,srcId,dstId)                            => Task.eval(storage.edgeAdd(routerID,msgTime,srcId,dstId)).fork.runAsync.onComplete(_ => eHandle(srcId,dstId))
    case RemoteEdgeAdd(routerID,msgTime,srcId,dstId,properties)           => Task.eval(storage.remoteEdgeAdd(routerID,msgTime,srcId,dstId,properties)).fork.runAsync.onComplete(_ => eHandleSecondary(srcId,dstId))
    case RemoteEdgeAddNew(routerID,msgTime,srcId,dstId,properties,deaths) => Task.eval(storage.remoteEdgeAddNew(routerID,msgTime,srcId,dstId,properties,deaths)).fork.runAsync.onComplete(_ => eHandleSecondary(srcId,dstId))
    case EdgeAddWithProperties(routerID,msgTime,srcId,dstId,properties)   => Task.eval(storage.edgeAdd(routerID,msgTime,srcId,dstId,properties)).fork.runAsync.onComplete(_ => eHandle(srcId,dstId))

    case EdgeRemoval(routerID,msgTime,srcId,dstId)                        => Task.eval(storage.edgeRemoval(routerID,msgTime,srcId,dstId)).fork.runAsync.onComplete(_ => eHandle(srcId,dstId))
    case RemoteEdgeRemoval(routerID,msgTime,srcId,dstId)                  => Task.eval(storage.remoteEdgeRemoval(routerID,msgTime,srcId,dstId)).fork.runAsync.onComplete(_ => eHandleSecondary(srcId,dstId))
    case RemoteEdgeRemovalNew(routerID,msgTime,srcId,dstId,deaths)        => Task.eval(storage.remoteEdgeRemovalNew(routerID,msgTime,srcId,dstId,deaths)).fork.runAsync.onComplete(_ => eHandleSecondary(srcId,dstId))

    case ReturnEdgeRemoval(routerID,msgTime,srcId,dstId)                  => Task.eval(storage.returnEdgeRemoval(routerID,msgTime,srcId,dstId)).fork.runAsync.onComplete(_ => eHandleSecondary(srcId,dstId))
    case RemoteReturnDeaths(msgTime,srcId,dstId,deaths)          => Task.eval(storage.remoteReturnDeaths(msgTime,srcId,dstId,deaths)).fork.runAsync.onComplete(_ => eHandleSecondary(srcId,dstId))

    case UpdatedCounter(newValue) => {
      managerCount = newValue
      storage.setManagerCount(managerCount)
    }
    case EdgeUpdateProperty(msgTime, edgeId, key, value)        => Task.eval(storage.updateEdgeProperties(msgTime, edgeId, key, value)).fork.runAsync   //for data coming from the LAM
    case e => println(s"Not handled message ${e.getClass} ${e.toString}")
 }

  def keepAlive() : Unit = mediator ! DistributedPubSubMediator.Send("/user/WatchDog", PartitionUp(managerID), localAffinity = false)

  /*****************************
   * Metrics reporting methods *
   *****************************/
  def getEntitiesPrevStates[T,U <: Entity](m : ParTrieMap[T, U]) : Int = {
    var ret = new AtomicInteger(0)
    m.foreach[Unit](e => {
      ret.getAndAdd(e._2.getPreviousStateSize())
    })
    ret.get
  }

  def reportSizes[T, U <: Entity](g : kamon.metric.GaugeMetric, map : ParTrieMap[T, U]) : Unit = {
    def getGauge(name : String) = {
     g.refine("actor" -> "PartitionManager", "replica" -> id.toString, "name" -> name)
    }

    Task.eval(getGauge("Total number of entities").set(map.size)).fork.runAsync
    //getGauge("Total number of properties") TODO

    Task.eval(getGauge("Total number of previous states").set(
      getEntitiesPrevStates(map)
    )).fork.runAsync

    // getGauge("Number of props previous history") TODO
  }

  def reportStdout() : Unit = {
    if (stdoutLog)
      println(s"TrieMaps size: ${storage.edges.size}\t${storage.vertices.size} | " +
              s"TreeMaps size: ${getEntitiesPrevStates(storage.edges)}\t${getEntitiesPrevStates(storage.vertices)}")
  }
  def reportIntake() : Unit = {
    //f(printing)
      //println(messageCount.get())

    // Kamon monitoring
    if (kLogging) {
      kGauge.refine("actor" -> "PartitionManager", "name" -> "messageCount", "replica" -> id.toString).set(messageCount.intValue())
      kGauge.refine("actor" -> "PartitionManager", "name" -> "secondaryMessageCount", "replica" -> id.toString).set(secondaryMessageCount.intValue())
      reportSizes(edgesGauge, storage.edges)
      reportSizes(verticesGauge, storage.vertices)
    }

    // Heap benchmarking
    //profile()

    // Reset counters
    messageCount.set(0)
    secondaryMessageCount.set(0)
  }

  def vHandle(srcID : Int) : Unit = {
    if(srcID%managerCount!=id){
      println(s"Received incorrect update $srcID with pm id $id")
    }
    messageCount.incrementAndGet()
  }

  def vHandleSecondary(srcID : Int) : Unit = {
    secondaryMessageCount.incrementAndGet()
  }
  def eHandle(srcID : Int, dstID : Int) : Unit = {
    if(srcID%managerCount!=id){
      println(s"Received incorrect update $srcID with pm id $id")
    }
    messageCount.incrementAndGet()
  }

  def eHandleSecondary(srcID : Int, dstID : Int) : Unit = {
    secondaryMessageCount.incrementAndGet()
  }

  /*********************************
   * END Metrics reporting methods *
   *********************************/
}