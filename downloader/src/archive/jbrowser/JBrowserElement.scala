package com.adcoelum.client.jbrowser

import java.net.URL

import com.adcoelum.client._
import com.adcoelum.common.Logger
import org.openqa.selenium.{By, WebElement}
import org.openqa.selenium.support.ui.ExpectedConditions


/**
  * Wrapper class that encapsulates an element drawn from JBrowserDriver to allow element manipulation including clicks
  *
  * @param page
  * @param element
  * @param browser
  * @param instance
  */
class JBrowserElement(page: JBrowserPage, element: WebElement, browser: JBrowserWebClient, instance: String) extends Element {

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
        var newPage = new JBrowserPage(page.getRequestUrl, page.getResponseUrl, browser.client.findElementByXPath("html"), browser, instance)
        val url = new URL(browser.client.getCurrentUrl)

        try {
            // Perform click on element
            Logger.trace(s"Click performed on element with name ${element.getTagName} on page ${url.toString}", Logger.MEGA_VERBOSE_INTENSITY)
            element.click
            browser.waiter.until(ExpectedConditions.presenceOfElementLocated(By.tagName("html")))

            // Obtain Url as a result of click
            val newUrl = new URL(browser.client.getCurrentUrl)

            // Obtain new page as a result of click
            newPage = new JBrowserPage(newUrl, newUrl, browser.client.findElementByXPath("html"), browser, instance)
        }
        catch {
            // Catch an errors
            case e: Throwable => {
                Logger.error(s"Unable to load page while attempting a click on page: ${url}", Logger.VERBOSE_INTENSITY, e)
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
}
