package com.raphtory.core.analysis
import akka.actor.{ActorContext, ActorRef}
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import com.raphtory.core.model.communication.{EdgeUpdateProperty, VertexMessage}
import com.raphtory.core.model.graphentities.{Edge, Property, Vertex}
import com.raphtory.core.storage.EntityStorage
import com.raphtory.core.utils.Utils

import scala.collection.parallel.ParSet
import scala.collection.parallel.mutable.ParArray
object VertexVisitor  {
  def apply(v : Vertex)(implicit context : ActorContext, managerCount : Int) = {
    new VertexVisitor(v)
  }
}
class VertexVisitor(v : Vertex)(implicit context : ActorContext, managerCount : Int) {

  private val mediator : ActorRef   = DistributedPubSub(context.system).mediator // get the mediator for sending cluster messages

  def getOutgoingNeighbors : ParArray[Int] = v.outgoingIDs.toParArray
  def getIngoingNeighbors  : ParArray[Int] = v.incomingIDs.toParArray

//  //<<<<<<< HEAD
//  def getOutgoingNeighbors : ParArray[Int] = v.outgoingEdges.values.map(e => e.getDstId).toParArray
//
//
//  //def getIngoingNeighbors  : ParArray[Int] = v.incomingEdges.values.map(e => e.getSrcId).toParArray
//  def getIngoingNeighbors  : ParSet[Int] = v.incomingIDs

  def getOutgoingNeighborProp(vId: Int, key : String) : Option[String] = {
    EntityStorage.edges.get(Utils.getEdgeIndex(v.vertexId,vId)) match {
      case Some(e) => e.getPropertyCurrentValue(key)
      case None    => None
    }
  }
  def getIngoingNeighborProp(vId : Int, key : String) : Option[String] = {
    EntityStorage.edges.get(Utils.getEdgeIndex(vId,v.vertexId)) match {
      case Some(e) => e.getPropertyCurrentValue(key)
      case None    => None
    }
  }

  def getNeighborsProp(key : String) : Vector[String] = {
    var values = Vector.empty[String]
    v.incomingEdges.foreach(e => {
      values :+= e._2.getPropertyCurrentValue(key).get
    })
    v.outgoingEdges.foreach(e => {
      values :+= e._2.getPropertyCurrentValue(key).get
    })
    values
  }

  def getPropertySet():ParSet[String] = {
    v.properties.keySet
  }




//=======
//>>>>>>> upstream/master
  def getPropertyCurrentValue(key : String) : Option[String] =
    v.properties.get(key) match {
      case Some(p) => Some(p.currentValue)
      case None => None
    }

  def updateProperty(key : String, value : String) = {
    v.synchronized {
      v.properties.get(key) match {
        case None =>
          v.properties.put(key, new Property(System.currentTimeMillis(), key, value))
        case Some(oldProp) => oldProp.update(System.currentTimeMillis(), value)
      }
    }
  }

  def messageNeighbour(vertexID : Int, message:VertexMessage) : Unit = {mediator ! DistributedPubSubMediator.Send(Utils.getReader(vertexID, managerCount), (vertexID,message), false)}

  def messageAllOutgoingNeighbors(message: VertexMessage) : Unit = v.outgoingIDs.foreach(vID => messageNeighbour(vID,message))

  def messageAllIngoingNeighbors(message: VertexMessage) : Unit = v.incomingIDs.foreach(vID => messageNeighbour(vID,message))

  def moreMessages():Boolean = v.messageQueue.isEmpty
  def nextMessage():VertexMessage = v.messageQueue.pop()

//  private def edgeFilter(srcId: Int, dstId: Int, edgeId : Long) : Boolean = Utils.getEdgeIndex(srcId, dstId) == edgeId
//  private def outgoingEdgeFilter(dstId : Int, edgeId : Long) : Boolean = edgeFilter(v.getId.toInt, dstId, edgeId)
//  private def ingoingEdgeFilter(srcId : Int, edgeId : Long) : Boolean = edgeFilter(srcId, v.getId.toInt, edgeId)
  //private def getNeighbor(f: Long => Boolean) : Option[Edge] = v.associatedEdges.values.find(e => f(e.getId))
  //private def getOutgoingNeighbor(vId : Int) = getNeighbor(e => outgoingEdgeFilter(vId, e))
  //private def getIngoingNeighbor(vId : Int) = getNeighbor(e => ingoingEdgeFilter(vId, e))



  //private def getNeighbors = v.associatedEdges
}

//def getOutgoingNeighbors : ParArray[Int] = v.outgoingEdges.values.map(e => e.getDstId).toParArray
//def getIngoingNeighbors  : ParArray[Int] = v.incomingEdges.values.map(e => e.getSrcId).toParArray

//  def getOutgoingNeighborProp(vId: Int, key : String) : Option[String] = {
//    v.outgoingEdges.get(Utils.getEdgeIndex(v.vertexId,vId)) match {
//      case Some(e) => e.getPropertyCurrentValue(key)
//      case None    => None
//    }
//  }
//  def getIngoingNeighborProp(vId : Int, key : String) : Option[String] = {
//    v.incomingEdges.get(Utils.getEdgeIndex(vId,v.vertexId)) match {
//      case Some(e) => e.getPropertyCurrentValue(key)
//      case None    => None
//    }
//  }