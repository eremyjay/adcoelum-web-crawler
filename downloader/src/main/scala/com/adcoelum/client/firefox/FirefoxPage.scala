package com.adcoelum.client.firefox


import java.net.URL
import java.util.concurrent.TimeUnit

import com.adcoelum.client._
import com.adcoelum.common.{Logger, Tools}
import com.adcoelum.downloader.Downloader
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.{By, StaleElementReferenceException, WebElement}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Wrapper class that represents a JBrowserDriver page
  *
  * @param requestUrl
  * @param responseUrl
  * @param page
  * @param browser
  * @param instance
  */
class FirefoxPage(requestUrl: URL, responseUrl: URL, var page: WebElement, browser: FirefoxWebClient, instance: String) extends Page {
    // Xml content of the page
    val xml = browser.client.getPageSource
    val DEFAULT_WAIT_TIME = 3000

    /**
      * Get the xml content of the page
      *
      * @return
      */
    def getXml: String = {
        xml
    }

    /**
      * Get the request Url
      *
      * @return
      */
    def getRequestUrl: URL = {
        requestUrl
    }

    /**
      * Get the response Url
      *
      * @return
      */
    def getResponseUrl: URL = {
        responseUrl
    }

    /**
      * Search for a list of elements that match an XPath query
      *
      * @param query
      * @return
      */
    def searchXPath(query: String): List[Element] = {
        // Perform the XPath query and establish a list for holding the elements
        Logger.trace(s"Perform XPath search with query:\n${query}\non page: ${requestUrl}", Logger.SUPER_VERBOSE_INTENSITY)
        val list: java.util.List[WebElement] = browser.client.findElements(By.xpath(query))
        val result: ListBuffer[Element] = ListBuffer.empty

        // Create wrapper elements for each result
        list.asScala foreach { element =>
            if (element.isDisplayed)
                result += new FirefoxElement(this, element, browser, instance)
        }

        result.toList
    }

    /**
      * Execute javascript over the page to get a page returned - this allows for direct manipulation of the page using Javascript
      * Following execution of the script, the page may change.  The new version of the page will be returned
      *
      * @param script
      * @return
      */
    def executeScriptForPage(script: String): Page = {
        // Execute the script and determine the result Url
        Logger.trace(s"Executing script\n${script}\nwith new page state: ${requestUrl}", Logger.HYPER_VERBOSE_INTENSITY)
        browser.client.executeScript(script)
        val responseUrl = new URL(browser.client.getCurrentUrl)

        // Relocate the HTML root of the page and return the result as a wrapper page
        browser.waiter.until(ExpectedConditions.presenceOfElementLocated(By.tagName("html")))
        browser.waiter.withTimeout(browser.conditionalTimeout, TimeUnit.SECONDS)
        browser.waiter.pollingEvery(browser.conditionalPolling, TimeUnit.MILLISECONDS)
        val page = browser.client.findElementByXPath("html")
        new FirefoxPage(responseUrl, responseUrl, page, browser, instance)
    }

    /**
      * Execute javascript over the page to get a value returned - this allows for direct manipulation of the page using Javascript
      * Following execution of the script, the page may change.  The result of the javascript executed is captured
      *
      * @param script
      * @return
      */
    def executeScriptForResult(script: String): String = {
        // Execute the script and capture the result
        Logger.trace(s"Executing script\n${script}\nto obtain result: ${requestUrl}", Logger.HYPER_VERBOSE_INTENSITY)
        val result = browser.client.executeScript(script)

        result.toString
    }

    /**
      * Method previously used in manipulation of pages for clicks - no longer in use
      *
      * @return
      */
    def getMethod: Integer = {
        // Create a request url including the browser ID
        val requestUrlIdentifier = {
            val urlString = requestUrl.toString
            if (urlString.contains("?"))
                s"${urlString}&BrowserID=${browser.browserID}"
            else
                s"${urlString}?BrowserID=${browser.browserID}"
        }

        Downloader.proxy.getMethod(requestUrlIdentifier)
    }

    /**
      * Peforms a refresh of the page by attempting to find the root HTML tag again
      */
    def refreshPage = {
        browser.waitForActions
        browser.waiter.until(ExpectedConditions.presenceOfElementLocated(By.tagName("html")))
        browser.waiter.withTimeout(browser.conditionalTimeout, TimeUnit.SECONDS)
        browser.waiter.pollingEvery(browser.conditionalPolling, TimeUnit.MILLISECONDS)
        page = browser.client.findElementByXPath("html")
    }

    /**
      * Determines whether the element provided to the method is still valid given the current state of the page
      *
      * @param element
      * @return
      */
    def testStale(element: WebElement): Boolean = {
        try {
            // Perform test of "staleness"
            element.findElements(By.id(""))
            false
        }
        catch {
            case e: StaleElementReferenceException => true
        }
    }

    /**
      * Perform a wait on the page, with a specific condition being awaited on
      * Allow a maximum amount of time using waitTime
      *
      * @param condition
      * @param waitTime
      * @return
      */
    def waitFor(condition: Boolean, waitTime: Integer = DEFAULT_WAIT_TIME): Boolean = {
        val start = System.currentTimeMillis

        // Determine whether wait time has been exceeded and condition is not met, else wait 1/10 of the waitTime
        while (System.currentTimeMillis < start + waitTime) {
            if (condition)
                return true
            else
                Tools.sleep(waitTime / 10 milliseconds)
        }

        Logger.trace(s"Waiting time ${waitTime} exceeded for condition page execution", Logger.SUPER_VERBOSE_INTENSITY)
        false
    }
}

