package com.adcoelum.error

/**
  * Created by jeremy on 5/03/2017.
  */
class TooManyRequestsException(code: Int) extends Exception {
    val statusCode = code

    override def getMessage: String = {
        s"Error code ${statusCode}"
    }
}
