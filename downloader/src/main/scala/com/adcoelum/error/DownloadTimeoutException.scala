package com.adcoelum.error

/**
  * Created by jeremy on 5/03/2017.
  */
class DownloadTimeoutException extends Exception {
    override def getMessage: String = {
        "Timeout for download request exceeded"
    }
}
