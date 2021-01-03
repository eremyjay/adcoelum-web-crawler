package com.adcoelum.client.firefox

import java.net.URL
import java.util.concurrent.TimeUnit

import com.adcoelum.client._
import com.adcoelum.common.{Configuration, Logger, Tools}
import com.adcoelum.downloader.Downloader
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium._


/**
  * Wrapper class that encapsulates an element drawn from JBrowserDriver to allow element manipulation including clicks
  *
  * @param page
  * @param element
  * @param browser
  * @param instance
  */
class FirefoxElement(page: FirefoxPage, element: WebElement, browser: FirefoxWebClient, instance: String) extends Element {
    val closeOverlays = (Configuration.config \ "downloader" \ "browser" \ "closeOverlays").as[Boolean]

    /**
      * Determine whether an element has a particular attribute
      *
      * @param name
      * @return
      */
    def hasAttribute(name: String): Boolean = {
        val attribute = element.getAttribute(name)
        attribute != null && attribute != ""
    }

    /**
      * Obtain an attribute from an element, if present
      *
      * @param name
      * @return
      */
    def getAttribute(name: String): String = {
        val attribute = element.getAttribute(name)
        if (attribute != null && attribute != "")
            attribute
        else
            ""
    }

    /**
      * Perform a click action on the page of the element
      *
      * @return
      */
    def doClick: Page = {
        // Get current page and current Url as a starting point
        var newPage = new FirefoxPage(page.getRequestUrl, page.getResponseUrl, browser.client.findElementByXPath("html"), browser, instance)
        val url = new URL(browser.client.getCurrentUrl)

        try {
            // Reset tracker for proxy to help establish if all items have been queried/captured in proxy
            Downloader.proxy.resetTracker(browser.browserID)

            // Perform click on element
            Logger.trace(s"Click attempted on element with name ${element.getTagName} on page ${url.toString}", Logger.HYPER_VERBOSE_INTENSITY)
            element.click

            // Wait for any actions to take place
            browser.waitForActions
            browser.waiter.until(ExpectedConditions.presenceOfElementLocated(By.tagName("html")))
            browser.waiter.withTimeout(browser.conditionalTimeout, TimeUnit.SECONDS)
            browser.waiter.pollingEvery(browser.conditionalPolling, TimeUnit.MILLISECONDS)

            // Obtain Url as a result of click
            val newUrl = new URL(browser.client.getCurrentUrl)

            // Perform a clean up of any pop-up windows
            browser.cleanUpExtraWindows

            // Obtain new page as a result of click
            newPage = new FirefoxPage(newUrl, newUrl, browser.client.findElementByXPath("html"), browser, instance)

            // Close any overlays (interstitial style windows) if enabled
            if (closeOverlays) {
                browser.client.executeScript("overlayRemoverRun()")
                Logger.trace(s"Performing removal of any overlays resulting from click on page ${url.toString}", Logger.HYPER_VERBOSE_INTENSITY)
            }
        }
        catch {
            // Catch an errors
            case e: InvalidElementStateException => {
                Logger.trace(s"Element not enabled - therefore click not performed on page ${url.toString}")
                // Reassign returned page to original page
                newPage = page
            }
            case e: ElementNotVisibleException => {
                Logger.trace(s"Element not visible - therefore click not performed on page ${url.toString}")
                // Reassign returned page to original page
                newPage = page
            }
            case e: ElementNotInteractableException => {
                Logger.trace(s"Element not interactable - therefore click not performed on page ${url.toString}")
                // Reassign returned page to original page
                newPage = page
            }
            case e: Throwable => {
                Logger.warn(s"Unable to load page while attempting a click on page: ${url}", Logger.VERBOSE_INTENSITY, e)
                // Reassign returned page to original page
                newPage = page
            }
        }

        newPage
    }

    /**
      * Convert element to a String by obtaining full tag
      *
      * @return
      */
    override def toString: String = {
        element.getAttribute("outerHTML")
    }

    /**
      * Convert element to a String by obtainin full tag
      *
      * @return
      */
    override def rawHtml = {
        element.getAttribute("outerHTML")
    }
}
