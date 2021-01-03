package com.adcoelum.client.jbrowser

import java.net.URL
import java.util
import java.util.concurrent.TimeUnit

import com.adcoelum.client._
import com.adcoelum.common.{Configuration, Logger, Tools}
import com.adcoelum.downloader.Downloader
import com.machinepublishers.jbrowserdriver._
import org.openqa.selenium.support.ui.{ExpectedConditions, WebDriverWait}
import org.openqa.selenium.{By, WebElement}

import scala.concurrent.duration._
import scala.language.postfixOps
import collection.JavaConverters._
import scala.util.Random


/**
  * Wrapper class for JBrowserDriver browser instance
  * This class provides the mechanics to run a browser instance using JBrowser driver, implementing the HttpClient trait
  */
class JBrowserWebClient extends HttpClient {
    var browserID = (Configuration.config \ "downloader" \ "browser" \ "browserName").as[String] + "_" + Random.nextLong.abs.toString
    var reloadBrowser = (Configuration.config \ "downloader" \ "browser" \ "reloadBrowser").as[Boolean]
    var reloadEveryXPages = (Configuration.config \ "downloader" \ "browser" \ "reloadEveryXPages").as[Int]
    Logger.info(s"Creating web client with ID: ${browserID}")

    val defaultTimeout = 60
    var internalTimeout = 5
    var reloadCounter = 0

    var client: JBrowserDriver = _
    var waiter: WebDriverWait = _

    if (!reloadBrowser) {
        // Establish client
        newClient
    }

    // Register this client with client registry
    ClientRegistry.add(this)

    // Register browser with proxy
    Downloader.proxy.registerBrowser(browserID)

    /**
      * Method to establish a new client using basic settings with default time metrics
      */
    def newClient = {
        Logger.debug(s"Creating browser for web client with ID: ${browserID}")

        // Establish the browser instance
        client = new JBrowserDriver(JBrowserWebConfig.settings(browserID))

        // Setup default timeout data
        client.manage.timeouts.pageLoadTimeout(defaultTimeout, TimeUnit.SECONDS)
        client.manage.timeouts.setScriptTimeout(defaultTimeout, TimeUnit.SECONDS)

        // Set browser logging level
        client.setLogLevel(Logger.webDriverLogLevelStd)

        // Wait for driver to load
        waiter = new WebDriverWait(client, 30, 500)
        waiter.withTimeout(30, TimeUnit.SECONDS)
        waiter.pollingEvery(500, TimeUnit.MILLISECONDS)
    }

    /**
      * Return the ID of the browser
      *
      * @return
      */
    def id: String = {
        browserID
    }

    /**
      * Return the status code for the page downloaded from the browser
      *
      * @return
      */
    def status: Int = {
        client.getStatusCode
    }

    /**
      * Method to get a page using the HTTP GET method
      * @param url
      * @return
      */
    def get(url: URL): Page = {
        // Test to make sure the browser is not dead
        try {
            client.toString

            if (reloadBrowser)
                reloadCounter += 1
        }
        catch {
            case e: Exception => {
                // Establish client
                Logger.debug(s"Discovered no active browser - launching a new browser for client $browserID")
                newClient

                if (reloadBrowser)
                    reloadCounter = 1
            }
        }

        // Reset tracker for proxy to help establish if all items have been queried/captured in proxy
        Downloader.proxy.resetTracker(browserID)

        // Get the page using the browser
        client.get(url.toString)
        // Wait until HTML tag is present
        waiter.until(ExpectedConditions.presenceOfElementLocated(By.tagName("html")))


        // Capture response URL
        val responseUrl = new URL(client.getCurrentUrl)
        // Capture instance handle
        val instance = client.getWindowHandle

        // Create page element and return
        val page: WebElement = client.findElementByXPath("html")
        new JBrowserPage(url, responseUrl, page, this, instance)
    }

    /**
      * Method to close browser window - currently unimplemented: TODO
      */
    def close = {
        if (reloadBrowser) {
            if (reloadCounter >= reloadEveryXPages) {
                Logger.debug(s"Closing browser client due to instruction to reload every $reloadEveryXPages pages for client $browserID")
                client.quit
                reloadCounter = 0
            }
        }
        else {
            // client.close
            // client.reset(JBrowserWebConfig.settings(browserID))
        }

    }

    /**
      *
      */
    def reset = {
        try {
            client.quit
        }
        catch {
            case e: Exception => Logger.warn("Attempted reset but browser is already dead", error = e)
        }

        if (reloadBrowser)
            reloadCounter = 0
        else
            newClient
    }


    /**
      * Method to shutdown (kill) the browser
      */
    def shutdown = {
        try {
            client.kill
        }
        catch {
            case e: Exception => Logger.warn(s"Failed to kill browser for client ${browserID}", error = e)
        }
    }


    def forward = {
        client.navigate.forward
    }


    def back = {
        client.navigate.back
    }

    /**
      * Method to wait for Ajax response
      * This is critical for pages that perform ajax processing upon a user action and/or on load
      * The browser will wait a specified amount of time to capture changes to the page and then allow for progress
      * This method has been developed heuristically to capture changes to a page as a result of ajax actions
      */
    def waitForActions: Unit = {
        // Initially wait 1/2 second for first time processing to allow an Ajax request to be made
        Tools.sleep(0.5 seconds)

        // Check if the proxy has no requests made from the browser for Ajax
        if (Downloader.proxy.trackerSize(browserID) == 0)
            return

        var counter = 0
        Logger.trace(s"Starting Ajax Wait on ${browserID}", Logger.SUPER_VERBOSE_INTENSITY)

        // Establish baseline for response from the server
        var previousResponseCount = Downloader.proxy.trackerSize(browserID)

        // Start testing wait time
        while (counter < defaultTimeout)
        {
            // Increment counter
            counter += 1

            // Track current response count
            val currentResponseCount = Downloader.proxy.trackerSize(browserID)

            // If tracker is zero
            if (previousResponseCount == 0) {
                // Commence internal count just in case there is a state change subsequently
                var internalCounter = 0

                // Start internal counter
                while (internalCounter < internalTimeout) {
                    // Increment counter and sleep for 1 second
                    internalCounter += 1
                    Tools.sleep(1 second)
                    Logger.trace(s"Waiting for internal counter in Ajax Wait on ${browserID} at ${internalCounter} seconds", Logger.SUPER_VERBOSE_INTENSITY)

                    // If state has changed, jump out as Ajax is unfinished
                    if (Downloader.proxy.trackerSize(browserID) > 0)
                        internalCounter = internalTimeout + 1 // Jump out but prevent return as there is more to process
                }

                // Signal waiting has finished
                if (internalCounter == internalTimeout) {
                    Logger.trace(s"Waiting for ajax finished on ${browserID}", Logger.SUPER_VERBOSE_INTENSITY)
                    return
                }
            }

            // Wait for 1 second
            previousResponseCount = currentResponseCount
            Logger.trace(s"Waiting for 1 second after ${counter - 1} seconds for Ajax Wait on ${browserID}")
            Tools.sleep(1 second)
        }
    }
}

/**
  * Create the WebConfig object for JBrowser to allow configuration of the browser to perform optimally
  */
object JBrowserWebConfig {

    /**
      * Define the settings for the browser based on a combination of predefined requirements and form settings
      * within the configuration file
      *
      * @param browserIdentifier
      * @return
      */
    def settings(browserIdentifier: String): Settings = {
        // Setup headers
        val headersMap: util.LinkedHashMap[String, String] = new util.LinkedHashMap[String, String]
        headersMap.put("Host", RequestHeaders.DYNAMIC_HEADER)
        headersMap.put("User-Agent", RequestHeaders.DYNAMIC_HEADER)
        headersMap.put("Cookie", RequestHeaders.DYNAMIC_HEADER)
        headersMap.put("Referer", RequestHeaders.DYNAMIC_HEADER)
        headersMap.put("BrowserID", browserIdentifier)
        val headers: RequestHeaders = new RequestHeaders(headersMap)

        // Set User Agent on which the browser will model behaviour
        val userAgent = {
            val agentString = (Configuration.config \ "downloader" \ "browser" \ "userAgent").as[String]

            if (agentString == "CHROME")
                UserAgent.CHROME
            else if (agentString == "TOR")
                UserAgent.TOR
            else
                UserAgent.CHROME
        }

        // Build the settings for the JBrowserDriver
        var settings = Settings.builder()
            .requestHeaders(headers)
            .userAgent(userAgent)
            .headless((Configuration.config \ "downloader" \ "browser" \ "headless").as[Boolean])
            .ajaxWait((Configuration.config \ "downloader" \ "browser" \ "ajaxWait").as[Int])
            .ajaxResourceTimeout((Configuration.config \ "downloader" \ "browser" \ "ajaxResourceTimeout").as[Int])
            .blockAds((Configuration.config \ "downloader" \ "browser" \ "blockAds").as[Boolean])
            .cache((Configuration.config \ "downloader" \ "browser" \ "cache").as[Boolean])
            .cacheEntries((Configuration.config \ "downloader" \ "browser" \ "cacheEntries").as[Int])
            .cacheEntrySize((Configuration.config \ "downloader" \ "browser" \ "cacheEntrySize").as[Int])
            .connectionReqTimeout((Configuration.config \ "downloader" \ "browser" \ "connectionReqTimeout").as[Int])
            .connectTimeout((Configuration.config \ "downloader" \ "browser" \ "connectTimeout").as[Int])
            .ignoreDialogs((Configuration.config \ "downloader" \ "browser" \ "ignoreDialogs").as[Boolean])
            .javascript((Configuration.config \ "downloader" \ "browser" \ "javascript").as[Boolean])
            .maxConnections((Configuration.config \ "downloader" \ "browser" \ "maxConnections").as[Int])
            .maxRouteConnections((Configuration.config \ "downloader" \ "browser" \ "maxRouteConnections").as[Int])
            .quickRender((Configuration.config \ "downloader" \ "browser" \ "quickRender").as[Boolean])
            .saveAttachments((Configuration.config \ "downloader" \ "browser" \ "saveAttachments").as[Boolean])
            .saveMedia((Configuration.config \ "downloader" \ "browser" \ "saveMedia").as[Boolean])
            .ssl((Configuration.config \ "downloader" \ "browser" \ "ssl").as[String])
            .logTrace((Configuration.config \ "downloader" \ "browser" \ "logTrace").as[Boolean])
            .logWarnings((Configuration.config \ "downloader" \ "browser" \ "logWarnings").as[Boolean])
            .logWire((Configuration.config \ "downloader" \ "browser" \ "logWire").as[Boolean])
            .javaOptions((Configuration.config \ "downloader" \ "browser" \ "javaOptions").as[String].split(" "):_*)
            //.logger((Configuration.config \ "downloader" \ "browser" \ "logger").as[String])

            if ((Configuration.config \ "downloader" \ "proxy" \ "enable").as[Boolean])
                settings = settings.proxy(new ProxyConfig(ProxyConfig.Type.HTTP, Downloader.proxy.proxy.getClientBindAddress.getHostAddress, Downloader.proxy.proxy.getPort))

            settings = settings.processes(Configuration.crawlerWorkerRoutees)

            settings.build()
    }
}
