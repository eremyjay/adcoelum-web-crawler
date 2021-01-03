package com.adcoelum.client.htmlunit

import com.gargoylesoftware.htmlunit.html.{DomAttr, DomElement, HtmlElement, HtmlPage}
import com.adcoelum.client.{Element, Page}
import com.gargoylesoftware.htmlunit.SgmlPage
import java.util.Map

/**
  * Created by jeremy on 2/07/2016.
  */
class HtmlUnitElement(inputElement: HtmlElement) extends Element {
    val element = inputElement

    override def hasAttribute(name: String): Boolean = {
        element.hasAttribute(name)
    }

    override def getAttribute(name: String): String = {
        element.getAttribute(name)
    }

    def doClick: Page = {
        val result: HtmlPage = element.click.asInstanceOf[HtmlPage]
        result.getWebClient.waitForBackgroundJavaScript(1000)
        val page: HtmlUnitPage = new HtmlUnitPage(result)
        page
    }
}
