package com.adcoelum.client

/**
  * Definition of the element trait for a web client
  */
trait Element {
    /**
      * Determine whether the element has a specific attribute
      *
      * @param name
      * @return
      */
    def hasAttribute(name: String): Boolean

    /**
      * Get the attribute of an element, if it exists
      *
      * @param name
      * @return
      */
    def getAttribute(name: String): String

    /**
      * Perform a click action on the element
      *
      * @return
      */
    def doClick: Page

    /**
      * Obtain the raw html
      *
      * @return
      */
    def rawHtml: String
}
