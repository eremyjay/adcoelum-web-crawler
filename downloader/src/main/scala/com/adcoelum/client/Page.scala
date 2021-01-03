package com.adcoelum.client

import java.net.URL

/**
  * Trait definition of a page for use with an Http Client
  */
trait Page {
    /**
      * Get the raw Xml for a page
      *
      * @return
      */
    def getXml: String

    /**
      * Method to obtain the original request Url
      *
      * @return
      */
    def getRequestUrl: URL

    /**
      * Method to obtain the final response Url
      *
      * @return
      */
    def getResponseUrl: URL

    /**
      * Method to obtain the elements from a search query in XPath
      *
      * @param query
      * @return
      */
    def searchXPath(query: String): List[Element]

    /**
      * Now defunct method to obtain the Http Method as part of the latest page behaviour
      *
      * @return
      */
    def getMethod: Integer

    /**
      * Perform a refresh of the page
      */
    def refreshPage

    /**
      * Execute javascript in order to get a particular returned result
      *
      * @param script
      * @return
      */
    def executeScriptForResult(script: String): String

    /**
      * Execute javascript in order to get a resulting page
      *
      * @param script
      * @return
      */
    def executeScriptForPage(script: String): Page
}


