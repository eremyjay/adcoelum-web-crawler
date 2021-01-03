package com.adcoelum.actors

import java.net.URL

import akka.actor.ActorRef

import scala.collection.mutable

/**
  * A special file for traits and case classes that are shared across multiple classes
  */
// Definition of categories for storage
trait Category
case class PropertyCategory() extends Category
case class MiscellaneousCategory() extends Category

case class StoreProperty(crawlMethod: Int, address: String, dataPoints: mutable.HashMap[String, Any], rawContent: String, content: String, dataIdentifier: String)
case class UpdateProperty(crawlMethod: Int, address: String, dataPoints: mutable.HashMap[String, Any], dataIdentifier: String)

case class ProcessPage(crawlMethod: Int, visits: Int, urlChain: List[URL], content: String)
case class ClassifyPage(url: URL, category: Category)

// Define case classes to handle various message types
case class CheckShouldDownloadPage(crawlMethod: Int, urlChain: List[URL])

case class NotifyUrlVisit(crawlMethod: Int, visits: Int, status: Int, urlChain: List[URL])
case class UpdateUrlHash(crawlMethod: Int, visits: Int, status: Int, urlChain: List[URL], hash: String)
case class UpdateCrawlFrontier(crawlMethod: Int, status: Int, urlChain: List[URL])

// Define case classes to handle various message types

case class ProcessLinks(crawlMethod: Int, urlChain: List[URL], page: String)
case class ProcessLinksList(crawlMethod: Int, urlChain: List[URL], links: List[URL])
case class TryToVisit(crawlMethod: Int, urlChain: List[URL])

// Define case classes to handle various message types
case class DownloadPage(crawlMethod: Int, visits: Int, urlChain: List[URL], domain: String, hash: String)
case class PageDownloaded(crawlMethod: Int, urlChain: List[URL])

case class UpdateRemoteSystems()

// Define case classes to handle various message types
case class TriggerActivity()
case class Activity(name: String, prevCount: Integer, currentCount: Integer)
case class RegisterRouter(name: String)
case class TerminateActivity()
case class Statistic(stat: String, data: Map[String, Any])

// Define case classes to handle various message types
case class ProcessBasicData(crawlMethod: Int, urlChain: List[URL], content: String, category: Category)
case class ProcessRawDataPoints(crawlMethod: Int, urlIdentifier: String, address: String, rawContent: String, content: String, category: Category, dataIdentifier: String)

// Define case classes to handle various message types
case class ProcessAdvancedDataPoints(crawlMethod: Int, address: String, rawContent: String, content: String, category: Category, dataIdentifier: String)


// Define case classes to handle various message types
case class ProcessLocation(crawlMethod: Int, rawDataPoints: mutable.HashMap[String, Any], rawContent: String, content: String, rawAddresses: List[Tuple3[Int, String, List[String]]], category: Category, dataIdentifier: String)
case class AddressInDatabaseCheckResult(crawlMethod: Int, rawDataPoints: mutable.HashMap[String, Any], rawContent: String, content: String, rawAddresses: List[Tuple3[Int, String, List[String]]], address: Option[String], category: Category, dataIdentifier: String)

// Define special case classes for training
case class ProcessToClassify(crawlMethod: Int, urlChain: List[URL], content: String, category: com.adcoelum.actors.Category)

// Messages for state
sealed trait State
case object Idle extends State
case object Active extends State
// When active, the timer will send Tick messages to the throttler
case object Tick
case class Queue(msg: Any)


case class AliveCheck(commander: String)

case class IdleCheck()
case class Ask(question: String)
case class Notify()

// Define case class to handle router initialisation
case class InitializeRouter()

case class Initialize()

case class AdjustThrottle(direction: Int, domain: String)

class SharedMessages {

}
