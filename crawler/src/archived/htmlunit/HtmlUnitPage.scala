package com.adcoelum.client.htmlunit

import java.io.{PrintWriter, StringWriter}
import java.net.URL

import com.adcoelum.client.{Element, Method, Page}
import com.gargoylesoftware.htmlunit.{HttpMethod, WebResponse, WebWindow}
import com.gargoylesoftware.htmlunit.html.{HtmlElement, HtmlPage}

import scala.collection.mutable.ListBuffer
import scala.collection.JavaConverters._

/**
  * Created by jeremy on 2/07/2016.
  */
class HtmlUnitPage(inputPage: HtmlPage) extends Page {
    val page = inputPage

    def getXml: String = {
        page.asXml
    }

    def getRequestUrl: URL = {
        page.getWebResponse.getWebRequest.getUrl
    }

    def getResponseUrl: URL = {
        page.getUrl
    }

    def searchXPath(query: String): List[Element] = {
        val data: java.util.List[_] = page.getByXPath(query)
        val list: ListBuffer[HtmlUnitElement] = ListBuffer.empty

        data.asScala foreach { element =>
            val result = element.asInstanceOf[HtmlElement]
            list += new HtmlUnitElement(result)
        }

        list.toList
    }

    def getMethod: Integer = {
        val method = page.getWebResponse.getWebRequest.getHttpMethod
        method match {
            case HttpMethod.GET => Method.GET
            case HttpMethod.POST => Method.POST
            case HttpMethod.DELETE => Method.DELETE
            case HttpMethod.HEAD => Method.HEAD
            case HttpMethod.OPTIONS => Method.OPTIONS
            case HttpMethod.PATCH => Method.PATCH
            case HttpMethod.PUT => Method.PUT
            case HttpMethod.TRACE => Method.TRACE
        }
    }

    def refreshPage = {

    }

    def executeScriptForPage(script: String): Page = {
        val result = page.executeJavaScript(script).getNewPage.asInstanceOf[HtmlPage]
        page.getWebClient.waitForBackgroundJavaScript(1000)
        new HtmlUnitPage(result)
    }

    def executeScriptForResult(script: String): String = {
        val result = page.executeJavaScript(script).getJavaScriptResult.toString
        page.getWebClient.waitForBackgroundJavaScript(1000)
        result
    }
}
