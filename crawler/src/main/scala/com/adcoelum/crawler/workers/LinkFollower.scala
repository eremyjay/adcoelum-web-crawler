package com.adcoelum.crawler.workers


import java.net.URL

import akka.actor.Actor
import com.adcoelum.actors.{Statistic, ProcessLinks, CheckShouldDownloadPage, TryToVisit, ProcessLinksList}
import com.adcoelum.common.{Configuration, Logger, Tools}

import scala.collection.mutable.ListBuffer
import scala.language.postfixOps
import scala.xml._


/**
  * The Link Follower class handles the search for links within a page to follow, currently designed for HTML pages
  * The class searches for anchor tags and extracts the href attribute for the link provided
  */
class LinkFollower extends Actor {
    // Establish connections to local actors
    val crawlFrontierRouter = context.actorSelection("/user/crawl_frontier_router")
    val activityMonitor = context.actorSelection("/user/activity_monitor")

    // Define link chain depth
    val linkChainDepth = (Configuration.config \ "crawler" \ "linkChainDepth").as[Int]

    // Define identifiers
    val queryIdentifier = "?"
    val anchorElement = "a"
    val hrefAttribute = "@href"

    /**
      * Establish the types of messages that will be handled by the actor
      *
      * @return
      */
    def receive = {
        // Handle the processing of links for a Url, given some content for that page
    	case ProcessLinks(crawlMethod, urlChain, content) => {
            // Pull source page url from url chain
            val url = urlChain.head

            // List of links processed
            var linksProcessed: ListBuffer[String] = ListBuffer.empty[String]
            var urlChainAsString: ListBuffer[String] = ListBuffer.empty[String]

            urlChain foreach { url =>
                urlChainAsString += Tools.urlToIdentifier(url)
            }

            try {
                // Convert the page to Xml to allow for queries
                val page = Tools.htmlToXml(Tools.stringDecompress(content))

                // Find all links on the page using an XPath search
                val links: NodeSeq = page \\ anchorElement

                Logger.trace(s"Processing total ${links.length} links found: ${url.toString}")

                // Process each link to see if it is usable
                var countLinks = 0
                links foreach { link =>
                    try {
                        // Obtain link Url
                        val linkUrl = new URL(url, (link \ hrefAttribute).text)

                        // Check to ensure if url is part of site being crawled
                        if (linkUrl.getHost == Configuration.rootUrl) {
                            val fullPath = Tools.urlToIdentifier(linkUrl)

                            // If the Url has not already come up on this page then attempt a visit
                            if (!linksProcessed.contains(fullPath) && !urlChainAsString.contains(fullPath)) {
                                // Add the link to processed links
                                linksProcessed += fullPath

                                // Add link url to chain, ensuring not longer than defined depth
                                val newUrlChain = linkUrl :: {
                                    if (urlChain.size >= linkChainDepth)
                                        urlChain.dropRight(1)
                                    else
                                        urlChain
                                }

                                // Attempt to visit the Url
                                Logger.trace(s"Attempting to visit link: ${fullPath}", Logger.SUPER_VERBOSE_INTENSITY)
                                tryVisit(crawlMethod, newUrlChain)
                                countLinks += 1
                            }
                        }
                    }
                    catch {
                        // Handle all failure cases
                        case e: java.util.NoSuchElementException => {
                            // Logger.warn(s"Bad anchor no href found") - handling cases where <a> is missing href
                            // Logging not required for this case
                        }
                        case e: java.net.MalformedURLException => {
                            // val stringLink = link.attr("href")
                            // Logger.warn(s"Bad link presented: ${stringLink}") - handling cases where Url is invalid
                            // Logging not required for this case
                        }
                        case e: java.net.ConnectException => Logger.error(s"Link check failed - connection error: ${url.toString}", error = e)
                        case e: Throwable => Logger.error(s"Error processing link ${(link \ hrefAttribute).text}: ${e.getMessage}", error = e)
                    }
                }

                Logger.trace(s"Number of links to follow ${countLinks} for ${url.toString}")
            }
            catch {
                case e: Throwable => Logger.warn(s"Failure while processing links in ${url.toString}: ${e.getMessage}", error = e)
            }
            finally {
                val statistics: Map[String, Any] = Map("links_processed" -> linksProcessed.size, "link_depth" -> urlChain.length)
                activityMonitor ! Statistic("links", statistics)
            }
    	}

        case ProcessLinksList(crawlMethod, urlChain, list) => {
            processLinksList(crawlMethod, urlChain, list)
        }

        // Handle message for attempting visit a page
        case TryToVisit(crawlMethod, urlChain) => {
            try {
                // Attempt to visit the page form the URL supplied
                tryVisit(crawlMethod, urlChain)
            }
            catch {
                case e: Throwable => Logger.error(s"Error trying to test visit: ${urlChain.head.toString}", error = e)
            }
        }
  	}

    /**
      * Attempt a page visit using this method
      * A check is performed by passing the Url on to Crawl Lister to determine whether it is worth visiting
      *
      * @param urlChain
      */
    def tryVisit(crawlMethod: Int, urlChain: List[URL]) = {
        // Pull url to visit from chain
        val url = urlChain.head

        // Ensure a host match for the crawler
        if (url.getHost == Configuration.rootUrl) {
            // Check to see if URL is already being processed
            crawlFrontierRouter ! CheckShouldDownloadPage(crawlMethod, urlChain)
        }
    }

    def processLinksList(crawlMethod: Int, urlChain: List[URL], links: List[URL]) = {
        // List of links processed
        var linksProcessed: ListBuffer[String] = ListBuffer.empty[String]
        var urlChainAsString: ListBuffer[String] = ListBuffer.empty[String]

        urlChain foreach { url =>
            urlChainAsString += Tools.urlToIdentifier(url)
        }

        // Process each link to see if it is usable
        var countLinks = 0
        links foreach { link =>
            try {
                // Check to ensure if url is part of site being crawled and not in existing chain
                if (link.getHost == Configuration.rootUrl) {
                    // Obtain link Url
                    val fullPath = Tools.urlToIdentifier(link)

                    // If the Url has not already come up then attempt a visit
                    if (!linksProcessed.contains(fullPath) && !urlChainAsString.contains(fullPath)) {
                        // Add the link to processed links
                        linksProcessed += fullPath

                        // Add link url to chain, ensuring not longer than defined depth
                        val newUrlChain = link :: {
                            if (urlChain.size >= linkChainDepth)
                                urlChain.dropRight(1)
                            else
                                urlChain
                        }

                        // Attempt to visit the Url
                        Logger.trace(s"Attempting to visit link: ${fullPath}", Logger.SUPER_VERBOSE_INTENSITY)
                        tryVisit(crawlMethod, newUrlChain)
                        countLinks += 1
                    }
                }
            }
            catch {
                // Handle all failure cases
                case e: java.util.NoSuchElementException => {
                    // Logger.warn(s"Bad anchor no href found") - handling cases where <a> is missing href
                    // Logging not required for this case
                }
                case e: java.net.MalformedURLException => {
                    // val stringLink = link.attr("href")
                    // Logger.warn(s"Bad link presented: ${stringLink}") - handling cases where Url is invalid
                    // Logging not required for this case
                }
                case e: Throwable => Logger.error(s"Error processing link ${link}: ${e.getMessage}", error = e)
            }
        }
    }
}