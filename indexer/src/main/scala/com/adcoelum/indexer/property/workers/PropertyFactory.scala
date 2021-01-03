package com.adcoelum.indexer.property.workers

import akka.actor.Actor
import akka.pattern.ask
import akka.util.Timeout
import com.adcoelum.common.Logger
import com.adcoelum.actors.{StoreProperty, UpdateProperty, PropertyCategory, ProcessRawDataPoints, ProcessAdvancedDataPoints}
import com.adcoelum.data.{Collection, DatabaseAccessor}
import reactivemongo.api.DefaultDB

import scala.collection.mutable.HashMap
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps


class PropertyFactory extends Actor {
    implicit val timeout = Timeout(5 seconds)

    val rawDataPointProcessRouter = context.actorSelection("/user/raw_data_point_process_router")
    val advancedDataPointProcessRouter = context.actorSelection("/user/advanced_data_point_process_router")

    val properties = DatabaseAccessor.collection("properties")
    val falseProperties = DatabaseAccessor.collection("false_properties")


    val dataSourcesIdentifier = "sources"
    val dataAttributesIdentifier = "attributes"
    val urlIdentifier = "url"
    val addressIdentifier = "address"
    val rawContentIdentifier = "plain_content"

    def receive = {
    	case StoreProperty(crawlMethod, address, dataPoints, rawContent, content, dataIdentifier) => {
    	    try {
                // Set Url
                val attributeDataPoints = s"$dataSourcesIdentifier.$dataIdentifier."
                val url = dataPoints(attributeDataPoints+urlIdentifier).asInstanceOf[String]

                dataPoints foreach { dataPoint =>
                    if (dataPoint._1.startsWith(attributeDataPoints))
                        dataPoints += (dataAttributesIdentifier+"."+dataPoint._1.replace(attributeDataPoints, "") -> dataPoint._2)
                }

                // Ensure Url is pulled out of data and data is stored as a map
                val data = (dataPoints += (attributeDataPoints+"last_update" -> System.currentTimeMillis)).toMap

        		// Use address as the unique identifying field
        		val isAddressIdentified = if (data.get(addressIdentifier).isEmpty) false else true

                Logger.debug(s"Storing property with ${data.size} data points: ${address}")

                isAddressIdentified match {
                    case true => {
                        val filter: Map[String, Any] = Map(addressIdentifier -> data(addressIdentifier))
                        properties.update(data, filter)

                        performMoreAnalysis(crawlMethod, address, rawContent, content, url.asInstanceOf[String], dataIdentifier)
                    }
                    case false => {
                        val filter: Map[String, Any] = Map(urlIdentifier -> url)
                        falseProperties.update(data, filter)
                    }
                }
        	}
            catch {
      	        case e: Throwable => Logger.warn(s"Write to Properties failed for Storing: ${e.getMessage}", error = e)
      	    }
	    }

        case UpdateProperty(crawlMethod, address, dataPoints, dataIdentifier) => {
            try {
                // Ensure data and data is stored as a map
                val attributeDataPoints = s"$dataSourcesIdentifier.$dataIdentifier."

                dataPoints foreach { dataPoint =>
                    if (dataPoint._1.startsWith(attributeDataPoints))
                        dataPoints += (dataAttributesIdentifier+"."+dataPoint._1.replace(attributeDataPoints, "") -> dataPoint._2)
                }

                val data = (dataPoints += (attributeDataPoints+"last_update" -> System.currentTimeMillis)).toMap

                Logger.trace(s"Updating property with ${data.size} data points: ${address}")

                val filter: Map[String, Any] = Map(addressIdentifier -> address)
                properties.update(data, filter)
            }
            catch {
                case e: Throwable => Logger.warn(s"Write to Properties failed for Updating: ${e.getMessage}", error = e)
            }
        }
  	}

    def performMoreAnalysis(crawlMethod: Int, address: String, rawContent: String, content: String, urlIdentifier: String, dataIdentifier: String): Unit = {
        rawDataPointProcessRouter ! ProcessRawDataPoints(crawlMethod, urlIdentifier, address, rawContent, content, PropertyCategory(), dataIdentifier)
        advancedDataPointProcessRouter ! ProcessAdvancedDataPoints(crawlMethod, address, rawContent, content, PropertyCategory(), dataIdentifier)
    }

}