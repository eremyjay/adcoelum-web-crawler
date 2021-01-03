package com.adcoelum.client

import java.net.{URL, URLConnection}
import java.util.concurrent.TimeUnit

import com.adcoelum.common.{Configuration, Logger, Tools}
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http._
import net.lightbody.bmp.BrowserMobProxyServer
import net.lightbody.bmp.filters.{ResponseFilter, ResponseFilterAdapter}
import net.lightbody.bmp.util.{HttpMessageContents, HttpMessageInfo}
import org.littleshoot.proxy.{HttpFilters, HttpFiltersAdapter, HttpFiltersSourceAdapter}
import play.api.libs.json.JsObject

import scala.collection._
import scala.collection.concurrent.TrieMap

/**
  * Definition of a proxy, acting as an intermediary between a browser and the wider world
  * This class is a wrapper class for BrowserMobProxyServer or any future implementation of a proxy
  * This class has the important role of performing logging, caching and tracking services for Ajax behaviour
  */
class Proxy {
    // Establishing the proxy and define basic behaviours
    val proxy = new BrowserMobProxyServer

    // Set configuration options
    val enableFilters = (Configuration.config \ "downloader" \ "proxy" \ "enableFilters").as[Boolean]
    val enableCache = (Configuration.config \ "downloader" \ "proxy" \ "enableCache").as[Boolean]
    val enableContentTypeFilters  = (Configuration.config \ "downloader" \ "proxy" \ "enableContentTypeFilters").as[Boolean]
    val enableDomainFilter  = (Configuration.config \ "downloader" \ "proxy" \ "enableDomainFilter").as[Boolean]
    val blockExtensions = (Configuration.config \ "downloader" \ "proxy" \ "blockExtensions").as[Boolean]
    val blockUrls = (Configuration.config \ "downloader" \ "proxy" \ "blockUrls").as[Boolean]
    val urlFilterConfig = (Configuration.config \ "downloader" \ "proxy" \ "urlFilter").as[String]
    val extensionFilterConfig = (Configuration.config \ "downloader" \ "proxy" \ "extensionFilter").as[String]
    val contentMatcherConfig = (Configuration.config \ "downloader" \ "proxy" \ "contentMatcher").as[String]
    val resourceMatcherConfig = (Configuration.config \ "downloader" \ "proxy" \ "resourceMatcher").as[String]
    val cacheMatcherConfig = (Configuration.config \ "downloader" \ "proxy" \ "cacheMatcher").as[String]

    // Establish matchers for content, resources and cache
    val contentMatcher = s"$contentMatcherConfig".r
    val resourceMatcher = s"$resourceMatcherConfig".r
    val cacheMatcher = s"$cacheMatcherConfig".r

    val replacerFilter: mutable.ListMap[String, Tuple2[String, String]] = {
        val replacerMap = mutable.ListMap.empty[String, Tuple2[String, String]]
        val replacers = (Configuration.config \ "downloader" \ "proxy" \ "replacers").as[JsObject]

        replacers.fields foreach { replacerVal =>
            val replacer = replacerVal._2

            val matcher = (replacer \ "contentMatcher").as[String]
            val find = (replacer \ "find").as[String]
            val replace = (replacer \ "replace").as[String]

            replacerMap += (matcher -> (find, replace))
        }

        replacerMap
    }

    val injectorFilter: mutable.ListMap[String, Tuple3[String, String, String]] = {
        val injectorMap = mutable.ListMap.empty[String, Tuple3[String, String, String]]
        val injectors = (Configuration.config \ "downloader" \ "proxy" \ "injectors").as[JsObject]

        injectors.fields foreach { injectorVal =>
            val injector = injectorVal._2

            val matcher = (injector \ "source").as[String]
            val find = (injector \ "find").as[String]
            val inject = (injector \ "inject").as[String]

            val injectorSourcePath = {
                val path = matcher
                if (!path.startsWith("/"))
                    System.getProperty("user.dir") + "/" + path + "/"
                else if (path.startsWith("./"))
                    System.getProperty("user.dir") + path.drop(1) + "/"
                else
                    path
            }

            var data = ""
            try {
                data = scala.io.Source.fromFile(injectorSourcePath).mkString
            }
            catch {
                case e: Throwable => {
                    Logger.error(s"Injection file could not be read: ${e.getMessage}", error = e)
                }
            }

            injectorMap += (matcher -> (find, inject, data))
        }

        injectorMap
    }

    // Determine valid filename and extension filters
    val filenameMatcher = "[/](.*)([?#].*)?$".r
    val urlFilter = s"(?i)($urlFilterConfig)(?-i)".r
    val extensionFilter = s"(?i)[.]($extensionFilterConfig)(?-i)".r

    // Define an empty Http response
    val emptyHttpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT, Unpooled.wrappedBuffer(new Array[Byte](0)))
    // emptyHttpResponse.headers.set("Connection", "close")

    // Establish the response tracker map for handling request and response tracking
    val responseTracker: concurrent.Map[String, concurrent.Map[String, Int]] = new TrieMap[String, concurrent.Map[String, Int]]

    // Establish a status tracker to allow querying of response code
    val statusTracker: mutable.ListMap[String, Int] = mutable.ListMap.empty[String, Int]

    // Establish http method tracker
    val methodTracker: mutable.ListMap[String, Int] = mutable.ListMap.empty[String, Int]

    // Establish url chain tracker
    val urlChainTracker: mutable.ListMap[String, List[URL]] = mutable.ListMap.empty[String, List[URL]]

    // Determine Har behaviours for detailed logging of performance
    // proxy.enableHarCaptureTypes(CaptureType.REQUEST_CONTENT)
    // proxy.newHar("browser")

    // Do not perform checks of validity of certificates
    proxy.setTrustAllServers(true)

    // Set up filters to be used if filtering is enabled
    if (enableFilters) {
        proxy.setConnectTimeout((Configuration.config \ "downloader" \ "proxy" \ "connectTimeout").as[Int], TimeUnit.SECONDS)
        proxy.setRequestTimeout((Configuration.config \ "downloader" \ "proxy" \ "connectionReqTimeout").as[Int], TimeUnit.SECONDS)
        proxy.setReadBandwidthLimit(Long.MaxValue)
        proxy.setWriteBandwidthLimit(Long.MaxValue)

        proxy.addLastHttpFilterFactory(new ResponseFilterAdapter.FilterSource(new ResponseFilter {
            /**
              * Define a proxy interface for proxy caching upon receiving an Http response
              *
              * @param response
              * @param contents
              * @param messageInfo
              */
            override def filterResponse(response: HttpResponse, contents: HttpMessageContents, messageInfo: HttpMessageInfo): Unit = {
                // Determine content type
                val contentType = contents.getContentType

                // Perform replacements on specific content types
                if (contents.isText) {
                    // Handle replacements
                    replacerFilter foreach { replacer =>
                        val contentTypeMatcher = replacer._1.r
                        val find = replacer._2._1
                        val replace = replacer._2._2

                        if (contentType != null && (contentTypeMatcher findFirstIn contentType).nonEmpty) {
                            Logger.trace(s"Replacing content based on match for ${contentTypeMatcher} - find ${find} and replace with ${replace}", Logger.HYPER_VERBOSE_INTENSITY)
                            val modifiedContent = contents.getTextContents.replaceAll(find, replace)
                            contents.setTextContents(modifiedContent)
                        }
                    }

                    // Handle injections
                    injectorFilter foreach { injector =>
                        val source = injector._1
                        val find = injector._2._1
                        val inject = injector._2._2
                        val data = injector._2._3

                        val replace = {
                            inject match {
                                case "before" => data + find
                                case "after" => find + data
                                case "replace" => data
                                case _ => ""
                            }
                        }

                        Logger.trace(s"Injecting content using ${inject} searching for ${find} using source ${source}", Logger.HYPER_VERBOSE_INTENSITY)
                        val modifiedContent = contents.getTextContents.replaceAll(find, replace)
                        contents.setTextContents(modifiedContent)
                    }
                }

                // If there is a valid content type for caching, apply caching of the content based on Url
                if (enableCache && contentType != null && (cacheMatcher findFirstIn contentType).isDefined) {
                    if (!ProxyCache.isCached(messageInfo.getUrl)) {
                        Logger.trace(s"Adding the following Url to the proxy cache: ${messageInfo.getUrl}", Logger.HYPER_VERBOSE_INTENSITY)
                        ProxyCache.cacheUrl(messageInfo.getUrl, response)
                    }
                }
            }
        }, Integer.MAX_VALUE))


        proxy.addFirstHttpFilterFactory(new HttpFiltersSourceAdapter {
            /**
              * Capture Http requests and performing filtering as required
              *
              * @param request
              * @return
              */
            override def filterRequest(request: HttpRequest): HttpFilters = {
                new HttpFiltersAdapter(request) {
                    // Reconstruct request Url
                    val host = request.headers.get("Host")
                    val protocol = {
                        if (host.contains(":443"))
                            "https"
                        else
                            request.getProtocolVersion.protocolName.toLowerCase
                    }

                    val url = {
                        if (request.getUri != "" && request.getUri.contains(host)) {
                            if (request.getUri.contains("://"))
                                new URL(request.getUri)
                            else
                                new URL(protocol + "://" + request.getUri)
                        }
                        else if (request.getUri == "" || !request.getUri.contains("/"))
                            new URL(protocol + "://" + host)
                        else
                            new URL(protocol + "://" + host + request.getUri)
                    }

                    // Obtain file details from Url
                    val fileSplit = url.getPath.split("/")
                    val filename = if (fileSplit.length > 1) fileSplit.last else ""

                    // Obtain browser ID for the purpose of tracking requests and responses
                    val browserID = {
                        if (request.headers.contains("BrowserID"))
                            request.headers.get("BrowserID")
                        else if (url.getQuery != null && url.getQuery.contains("BrowserID")) {
                            val regex = "BrowserID=([a-zA-Z_0-9]+)".r
                            val result = regex findFirstMatchIn url.getQuery
                            result.get.subgroups(0)
                        }
                        else
                            ""
                    }

                    /**
                      * Jump in at the point of client hitting the proxy to provide cached or empty responses where necessary
                      *
                      * @param httpObject
                      * @return
                      */
                    override def clientToProxyRequest(httpObject: HttpObject): HttpResponse = {
                        Logger.trace(s"Request made from Client to Proxy of type ${request.getMethod.toString} - ${request.getUri} with headers: ${request.headers.entries.toString}", Logger.HYPER_VERBOSE_INTENSITY)

                        // Handle loading a blank page only - no tracking required
                        if (request.headers.get("Host") == "_blank") {
                            Logger.trace(s"Providing blank page as blank canvas", Logger.HYPER_VERBOSE_INTENSITY)
                            return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer("<html><head><title></title></head><body></body></html>".getBytes))
                        }

                        // Track new requests, so long as the request is not a CONNECT (usually associated with establishing SSL)
                        if (request.getMethod != HttpMethod.CONNECT && httpObject.isInstanceOf[HttpRequest]) {
                            responseTracker(browserID).put(url.toString, 1)
                            Logger.trace(s"Tracking request made - new count: ${responseTracker.size} with type ${request.getMethod.toString} - ${request.getUri}", Logger.HYPER_VERBOSE_INTENSITY)
                        }

                        // Deal with filtering requests except where basic CONNECT (usually associated with establishing SSL)
                        if (request.getMethod != HttpMethod.CONNECT && httpObject.isInstanceOf[HttpRequest]) {
                            // If the content has been cached, return the cached version
                            if (enableCache && ProxyCache.isCached(filename)) {
                                Logger.trace(s"Returning cached content ${filename} for ${url.toString}", Logger.HYPER_VERBOSE_INTENSITY)
                                return ProxyCache.getObject(filename)
                            }

                            // Block any unwanted urls and return an empty response
                            if (blockUrls && (urlFilter findFirstIn host).isDefined) {
                                Logger.trace(s"Returning empty response for unwanted content based on host ${host} for ${url.toString}", Logger.HYPER_VERBOSE_INTENSITY)
                                return emptyHttpResponse.copy
                            }

                            // Block any unwanted extensions and return an empty response
                            if (blockExtensions && (extensionFilter findFirstIn filename).isDefined) {
                                Logger.trace(s"Returning empty response for unwanted content based on filename ${filename} for ${url.toString}", Logger.HYPER_VERBOSE_INTENSITY)
                                return emptyHttpResponse.copy
                            }

                            if (enableContentTypeFilters) {
                                // Check by content type whether to continue
                                val check: URLConnection = url.openConnection
                                val contentType = check.getContentType

                                if (contentType != null && (contentMatcher findFirstIn contentType).isEmpty) {
                                    Logger.trace(s"Returning empty response for unwanted content based on content type ${contentType} for ${url.toString}", Logger.HYPER_VERBOSE_INTENSITY)
                                    return emptyHttpResponse.copy
                                }

                                // Block non-matching host page redirects
                                if (url.getHost != urlChainTracker(browserID).last.getHost && contentType != null && (resourceMatcher findFirstIn contentType).isEmpty) {
                                    Logger.trace(s"Returning empty response for unwanted content based on non-matching host and resource ${url.toString}", Logger.HYPER_VERBOSE_INTENSITY)
                                    return emptyHttpResponse.copy
                                }
                            }

                            if (enableDomainFilter) {
                                // Block anything where the host data does not match
                                if (url.getHost != urlChainTracker(browserID).last.getHost) {
                                    Logger.trace(s"Returning empty response for unwanted content based on non-matching host ${url.toString}", Logger.HYPER_VERBOSE_INTENSITY)
                                    return emptyHttpResponse.copy
                                }
                            }
                        }

                        super.clientToProxyRequest(httpObject)
                    }

                    /**
                      * Add headers and perform tracking for proxy to client responses
                      *
                      * @param httpObject
                      * @return
                      */
                    override def proxyToClientResponse(httpObject: HttpObject): HttpObject = {
                        // Determine whether response is a valid Http response - if so and within response tracker, perform handling
                        if (httpObject.isInstanceOf[HttpResponse] && responseTracker(browserID).contains(url.toString)) {
                            Logger.trace(s"Valid response made from Proxy to Client - current count ${responseTracker(browserID).size} - ${request.getUri} with headers ${httpObject.asInstanceOf[HttpResponse].headers.entries.toString}", Logger.HYPER_VERBOSE_INTENSITY)
                            responseTracker(browserID).remove(url.toString)
                            httpObject.asInstanceOf[HttpResponse].headers.add("Keep-Alive", "5000")
                        }

                        super.proxyToClientResponse(httpObject)
                    }

                    /**
                      * Handling request from proxy to server
                      *
                      * @param httpObject
                      * @return
                      */
                    override def proxyToServerRequest(httpObject: HttpObject): HttpResponse = {
                        Logger.trace(s"Request made to Server from Proxy of type ${request.getMethod.toString} - ${request.getUri} with headers: ${request.headers.entries.toString}", Logger.HYPER_VERBOSE_INTENSITY)

                        super.proxyToServerRequest(httpObject)
                    }

                    /**
                      * Handling response from server to proxy
                      *
                      * @param httpObject
                      * @return
                      */
                    override def serverToProxyResponse(httpObject: HttpObject): HttpObject = {
                        if (httpObject.isInstanceOf[HttpResponse]) {
                            val result = httpObject.asInstanceOf[HttpResponse]
                            val code = result.getStatus.toString
                            val headers = result.headers.entries.toString
                            Logger.trace(s"Response made from Server to Proxy for ${request.getUri} with result ${code} with headers: ${headers}", Logger.HYPER_VERBOSE_INTENSITY)

                            if (statusTracker.size >= 500)
                                statusTracker.dropRight(1)

                            statusTracker(Tools.urlToIdentifier(url)) = httpObject.asInstanceOf[HttpResponse].getStatus.code

                            if (methodTracker.size >= 500)
                                methodTracker.dropRight(1)

                            request.getMethod match {
                                case HttpMethod.GET => methodTracker(url.toString) = Method.GET
                                case HttpMethod.POST => methodTracker(url.toString) = Method.POST
                                case HttpMethod.DELETE => methodTracker(url.toString) = Method.DELETE
                                case HttpMethod.HEAD => methodTracker(url.toString) = Method.HEAD
                                case HttpMethod.OPTIONS => methodTracker(url.toString) = Method.OPTIONS
                            }
                        }

                        super.serverToProxyResponse(httpObject)
                    }
                }
            }
        })
    }

    /**
      * Start the proxy and load the blacklist
      */
    def start = {
        proxy.start
        Logger.debug(s"Starting proxy service for processing requests at ${Configuration.responderAddress} on port ${proxy.getPort}")
    }

    /**
      * Stop the proxy
      */
    def stop = {
        proxy.stop
    }

    /**
      * Register a browser with the proxy for request and response tracking
      *
      * @param identifier
      */
    def registerBrowser(identifier: String) = {
        responseTracker(identifier) = new TrieMap[String, Int]
    }

    /**
      * Reset the tracker for a particular browser
      *
      * @param identifier
      */
    def resetTracker(identifier: String) = {
        responseTracker(identifier).clear
    }

    /**
      * Determine the current size of requests that are open for the tracker
      *
      * @param identifier
      * @return
      */
    def trackerSize(identifier: String) = {
        responseTracker(identifier).size
    }


    /**
      * Obtain the status code for a particular request and response based on url
      *
      * @param url
      * @return
      */
    def getStatusCode(url: URL): Int = {
        val urlAsIdentifier = Tools.urlToIdentifier(url)
        statusTracker.getOrElse(urlAsIdentifier, 0)
    }


    def getMethod(urlAsIdentifier: String): Int = {
        methodTracker.getOrElse(urlAsIdentifier, -1)
    }

    def setURLChain(browser: String, urlChain: List[URL]) = {
        urlChainTracker(browser) = urlChain
    }
}


