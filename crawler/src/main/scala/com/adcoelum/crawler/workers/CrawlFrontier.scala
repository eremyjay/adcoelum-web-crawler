package com.adcoelum.crawler.workers

import java.net.{URL, URLConnection}

import akka.actor.{Actor, ActorSelection}
import akka.pattern.ask
import akka.util.Timeout
import com.adcoelum.actors._
import com.adcoelum.common._
import com.adcoelum.crawler.Crawler
import com.adcoelum.data.{Cache, CacheConnectionData, DatabaseAccessor}
import com.aerospike.client._

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps







/**
  * Crawl Lister is the class to manage the list of URLs that have been visited by the crawler application
  * It is used as the basis for avoid visiting pages on more than one occasion and to provide a means
  * for determining future recrawl strategies.
  */
class CrawlFrontier extends Actor {
    implicit val timeout = Timeout(5 seconds)

	// Connect to the data collection
	val commandControlData = DatabaseAccessor.collection("command_control")

	// Establish connections to local actors
	val activityMonitor = context.actorSelection("/user/activity_monitor")

	// Establish connections to remote actors - treat as a method call
	var downloaderSystems: List[Tuple3[String, String, Int]] = List.empty[Tuple3[String, String, Int]]

	// Get a list of current available downloader systems
	downloaderSystemsList(setDownloaderSystems)

	// Define matchers
	val documentMatcher = (Configuration.config \ "crawler" \ "documentMatcher").as[String].r
	val excludeMatcher = (Configuration.config \ "crawler" \ "excludeMatcher").as[String].r


	// Connect to crawling cache
	val cache = DatabaseAccessor.cache

	// Define identifiers
	val lastCrawlIdentifier = "last_crawl"
	val queryIdentifier = "?"
	val hashIdentifier = "hash"
	val propertyIdentifier = "property"
	val domainIdentifier = "domain"
	val scoreIdentifier = "score"
	val visitsIdentifier = "visits"
	val statusIdentifier = "status"
	val instanceIdentifier = "instance"

	/**
	  * Establish the types of messages that will be handled by the actor
	  *
	  * @return
	  */
	def receive = {
    	// Capture and recognise Url being visited
    	case NotifyUrlVisit(crawlMethod, visits, status, urlChain) => {
			val url = urlChain.head
	        updateCrawlFrontierTimeCache(url, visits, status, urlChain)
    	}

    	// Capture hash for page and note
    	case UpdateUrlHash(crawlMethod, visits, status, urlChain, hash) => {
			val url = urlChain.head
    	    updateCrawlFrontierHashCache(url, visits, status, hash, urlChain)
    	}

		// Determine whether page should be visited
		case CheckShouldDownloadPage(crawlMethod, urlChain) => {
			determineShouldDownloadPageBasedOnCache(crawlMethod, urlChain)
		}

		case UpdateRemoteSystems() => {
			downloaderSystemsList(setDownloaderSystems)
		}

		case PageDownloaded(crawlMethod, urlChain) => {
			registerPageDownloaded(crawlMethod, urlChain)
		}
  	}

	/**
	  * This method is used to update the cache to reflect the time at which an Url is visited
	  *
	  * @param url
	  */
  	def updateCrawlFrontierTimeCache(url: URL, visits: Int, status: Int, urlChain: List[URL]) = {
  	    try {
			// Simplify the Url for identification purposes
			val fullPath = Tools.urlToIdentifier(url)

			// Perform write to cache
			cache.put(fullPath, Map.empty + (lastCrawlIdentifier -> System.currentTimeMillis, domainIdentifier -> Configuration.rootUrl,
											visitsIdentifier -> visits, statusIdentifier -> status, instanceIdentifier -> Crawler.instance))
  	    }
  	    catch {
			// Handle any cache write fails
  	        case e: Throwable => Logger.error(s"Update to Crawl List with Time fail: ${e.getMessage}", error = e)
  	    }
  	}

	/**
	  * This method is used to update the cache to reflect the current hash for the Url visited
	  *
	  * @param url
	  * @param hash
	  */
  	def updateCrawlFrontierHashCache(url: URL, visits: Int, status: Int, hash: String, urlChain: List[URL]) {
  	    try {
			// Simplify the Url for identification purposes
			val fullPath = Tools.urlToIdentifier(url)

			// Perform write to cache
			cache.put(fullPath, Map(lastCrawlIdentifier -> System.currentTimeMillis, domainIdentifier -> Configuration.rootUrl,
									visitsIdentifier -> visits, hashIdentifier -> hash, statusIdentifier -> status,
									instanceIdentifier -> Crawler.instance))
  	    }
        catch {
			// Handle any cache write fails
            case e: Throwable => Logger.error(s"Update to Crawl List with Hash fail: ${e.getMessage}", error = e)
        }
  	}

	/**
	  * This method is used to update the cache to reflect that a url is never to be visited
	  *
	  * @param url
	  */
	def updateCrawlFrontierNeverVisitCache(url: URL, visits: Int, urlChain: List[URL]) = {
		try {
			// Simplify the Url for identification purposes
			val fullPath = Tools.urlToIdentifier(url)

			// Perform write to cache
			cache.put(fullPath, Map.empty + (lastCrawlIdentifier -> System.currentTimeMillis, domainIdentifier -> Configuration.rootUrl,
											 visitsIdentifier -> visits, statusIdentifier -> 410,
											 instanceIdentifier -> Crawler.instance, scoreIdentifier -> 0))
		}
		catch {
			// Handle any cache write fails
			case e: Throwable => Logger.error(s"Update to Crawl List with Time fail: ${e.getMessage}", error = e)
		}
	}


	/**
	  * This method is used to update the cache to reflect a new url or existing url to be visited
	  *
	  * @param url
	  */
	def updateCrawlFrontierToVisitCache(url: URL, urlChain: List[URL], visits: Int = 0) = {
		try {
			// Simplify the Url for identification purposes
			val fullPath = Tools.urlToIdentifier(url)

			// Perform write to cache
			cache.put(fullPath, Map.empty + (domainIdentifier -> Configuration.rootUrl, visitsIdentifier -> visits, instanceIdentifier -> Crawler.instance))
		}
		catch {
			// Handle any cache write fails
			case e: Throwable => Logger.error(s"Update to Crawl List with Time fail: ${e.getMessage}", error = e)
		}
	}


	/**
	  * This method is used to determine whether a page should be visited (yet) given certain conditions
	  *
	  * @param urlChain
	  */
	def determineShouldDownloadPageBasedOnCache(crawlMethod: Int, urlChain: List[URL]) = {
		// Obtain url from chain
		val url = urlChain.head

		// Simplify the Url for identification purposes
		val fullPath = Tools.urlToIdentifier(url)
		val pathQuery = Tools.urlToPathQuery(url)

		if ((excludeMatcher findFirstIn pathQuery).isDefined) {
			Logger.trace(s"Url contains exclude matcher case and therefore will not be visited: ${url}")

			// Notify that page has been included as if visited
			updateCrawlFrontierNeverVisitCache(url, 1, urlChain)
		}
		else {
			// Start by opening a connection to the server to establish the content type of the Url, if possible
			val check: URLConnection = url.openConnection
			val contentType = check.getContentType

			Logger.trace(s"Content Type returned ${contentType} for url: ${url.toString}", Logger.MEGA_VERBOSE_INTENSITY)

			// Determine if match to acceptable Types: text/*, application/xml, or application/xhtml+xml
			if ((contentType != null && (documentMatcher findFirstIn contentType).isDefined) || contentType == null) {
				try {
					// Query from cache based on key
					val result = cache.get(fullPath)

					// If the query does not present a result, then queue the page for download
					if (result.isEmpty) {
						// Notify that pages are to be visited
						updateCrawlFrontierToVisitCache(url, urlChain)

						// Queue the Url
						activityMonitor ! Statistic("frontier", Map("frontier_increment" -> 1))
						getDownloadQueuer ! Queue(DownloadPage(crawlMethod, 0, urlChain, Crawler.crawlUrl.getHost, ""))
					}
					// Handle all other cases where a result is returned
					else {
						// Obtain hash and determine if ready to visit based on delay factor
						val visits = {
							result.getOrElse(visitsIdentifier, 0) match {
								case i: Int => i
								case l: Long => l.toInt
							}
						}

						val score = {
							result.getOrElse(scoreIdentifier, Configuration.revisitScore) match {
								case i: Int => i
								case l: Long => l.toInt
							}
						}

						val status = {
							result.getOrElse("status", 0) match {
								case i: Int => i
								case l: Long => l.toInt
							}
						}

						val hash = result.getOrElse(hashIdentifier, "").asInstanceOf[String]
						val instance = result.getOrElse(instanceIdentifier, "").asInstanceOf[String]

						val isProperty = {
							result.getOrElse(propertyIdentifier, 0) match {
								case i: Int => i
								case l: Long => l.toInt
							}
						}
						val readyToVisit = if (System.currentTimeMillis - result.getOrElse(lastCrawlIdentifier, 0L).asInstanceOf[Long] > Configuration.revisitDelay.toMillis) true else false

						// If ready to visit and either not visited or score matched, visit page and provide hash where present
						if (instance != Crawler.instance && readyToVisit &&
							(crawlMethod == Configuration.BROAD_CRAWL || (visits == 0 || score >= Configuration.revisitScore)) &&
							status < 400) {
							// Notify that pages are to be visited
							updateCrawlFrontierToVisitCache(url, urlChain, visits)

							// Set method based on visits
							val method = {
								if (Crawler.crawlMethod == Configuration.BROAD_CRAWL || visits == 0)
									Configuration.BROAD_CRAWL
								else
									Configuration.FOCUS_CRAWL
							}

							if ((method == Configuration.BROAD_CRAWL && urlChain.length <= Configuration.crawlBroadDepth) ||
								(method == Configuration.FOCUS_CRAWL && urlChain.length <= Configuration.crawlFocusDepth)) {
								// Queue the Url
								activityMonitor ! Statistic("frontier", Map("frontier_increment" -> 1))
								Logger.trace(s"Queuing due to condition state - crawl method: ${crawlMethod}, last instance: ${instance}, ready: ${readyToVisit}, visits: ${visits}, score: ${score}: ${url}", Logger.ULTRA_VERBOSE_INTENSITY)
								getDownloadQueuer ! Queue(DownloadPage(method, visits, urlChain, Crawler.crawlUrl.getHost, hash))
							}
						}
						else
							Logger.trace(s"Not queuing due to condition state - crawl method: ${crawlMethod}, last instance: ${instance}, ready: ${readyToVisit}, visits: ${visits}, score: ${score}: ${url}", Logger.ULTRA_VERBOSE_INTENSITY)
						/*
						else if (isProperty == 1) {
							// Set scoring for page chain except actual page itself
							ScoringEngine.scorePages(urlChain, PropertyScore(true), crawlMethod)
						}
						*/
					}
				}
				catch {
					case e: AerospikeException => {
						// Handle non-exception where key is not present - want to download the Url
						if (e.getResultCode == ResultCode.KEY_NOT_FOUND_ERROR) {
							// Notify that pages are to be visited
							updateCrawlFrontierToVisitCache(url, urlChain)

							// Queue the Url
							activityMonitor ! Statistic("frontier", Map("frontier_increment" -> 1))
							getDownloadQueuer ! Queue(DownloadPage(crawlMethod, 0, urlChain, Crawler.crawlUrl.getHost, ""))
						}
						// Handle case where an aerospike failure occurs
						else
							Logger.error(s"Query Crawl List Confirm Page Downloaded Aerospike fail: ${e.getMessage}", error = e)
					}
					// Handle all other failures
					case e: Throwable => Logger.error(s"Query Crawl List Confirm Page Downloaded fail: ${e.getMessage}", error = e)
				}
			}
			else {
				Logger.trace(s"Content type ${contentType} does not meet requirements of matcher for url ${url}")

				// Notify that page has been included as if visited
				updateCrawlFrontierNeverVisitCache(url, 1, urlChain)
			}
		}
	}

	// Callback function to perform the set
	def setDownloaderSystems(list: List[Tuple3[String, String, Int]]) = {
		downloaderSystems = list
	}


	def getDownloadQueuer: ActorSelection = {
		if (downloaderSystems.isEmpty) {
			Logger.warn("No downloaders available to set a router", error = new Exception("No downloaders available"))
			context.actorSelection("")
		}
		else {
			val rand = scala.util.Random
			val chosen = {
				if (downloaderSystems.size > 1)
					rand.nextInt(downloaderSystems.size)
				else
					0
			}

			val selectionString = s"akka.tcp://${downloaderSystems(chosen)._1}@${downloaderSystems(chosen)._2}:${downloaderSystems(chosen)._3}/user/download_queue_router"
			Logger.trace(s"Page Download Queuer used: $selectionString", Logger.ULTRA_VERBOSE_INTENSITY)
			context.actorSelection(selectionString)
		}
	}

	def downloaderSystemsList(callback: Function1[List[Tuple3[String, String, Int]], Unit]) = {
		try {
			val query = Map.empty[String, Any]
			val projection = Map("host" -> 1, "downloaders" -> 1)

			// Return the results based on the search
			def action(results: List[Map[String, Any]]) = {
				var downloaders: ListBuffer[Tuple3[String, String, Int]] = ListBuffer.empty[Tuple3[String, String, Int]]

				results foreach { result =>
					if (result.contains("host") && result.contains("downloaders")) {
						val host = result("host").asInstanceOf[String]

						result("downloaders").asInstanceOf[Map[String, Map[String, Any]]] foreach { indexMap =>
							val name = indexMap._1
							val port = indexMap._2.asInstanceOf[Map[String, Any]].getOrElse("systemPort", 0).asInstanceOf[Int]
							downloaders += new Tuple3(name, host, port)
						}
					}
				}

				callback(downloaders.toList)
			}

			commandControlData.read(query, projection, action)
		}
		catch {
			case e: Throwable => Logger.warn(s"Obtain indexer map failed: ${e.getMessage}", error = e)
		}
	}

	def registerPageDownloaded(crawlMethod: Int, urlChain: List[URL]) = {
		// Decrement the frontier counter
		activityMonitor ! Statistic("frontier", Map("frontier_decrement" -> 1, "page_downloaded" -> 1))
	}
}



