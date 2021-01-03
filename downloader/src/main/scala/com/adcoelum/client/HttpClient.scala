package com.adcoelum.client

import java.net.URL

/**
  * Http Client trait to define implementations of a client
  */
trait HttpClient {
    /**
      * Method to obtain a page based on a provided Url
      *
      * @param url
      * @return
      */
    def get(url: URL): Page

    /**
      * Method to wait for Ajax to perform instructions or for a page to perform some actions
      */
    def waitForActions

    /**
      * Create a new client instance
      */
    def newClient

    /**
      * Obtain the ID of the client browser
      *
      * @return
      */
    def id: String

    /**
      * Obtain the relevant window handle for the client browser
      * @return
      */
    def handle: String

    /**
      * Obtain the HTTP status of the current page
      *
      * @return
      */
    def status: Int

    /**
      * Close a browser window
      */
    def close

    /**
      * Reset the browser
      */
    def reset

    /**
      * Close the client
      */
    def shutdown

    def forward
    def back
}

object Method {
    val GET = 0
    val POST = 1
    val DELETE = 2
    val HEAD = 3
    val OPTIONS = 4
    val PATCH = 5
    val PUT = 6
    val TRACE = 7
}
