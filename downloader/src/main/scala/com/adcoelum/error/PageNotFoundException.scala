package com.adcoelum.error

/**
  * Created by jeremy on 6/03/2017.
  */
class PageNotFoundException(code: Int) extends Exception {
    val statusCode = code

    override def getMessage: String = {
        s"Error code ${statusCode}"
    }
}
