package com.adcoelum.network

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.net._

import com.adcoelum.common.{Configuration, Logger}

import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * The client class handles network connections acting as a client connecting to a server over TCP.  The class handles the basic
  * java network layer configuration and provides a programmatic interface for connections.
  */
class Client(portNumber: Integer, hostName: String = Configuration.host) {
    // Establish connection variables
    val defaultTimeout = 5 seconds
    var connection: Socket = _
    var writer: PrintWriter = _
    var reader: BufferedReader = _

    /**
      * The connect methods provides an interface for handling network connection and the reader and writer classes
      * for sending and receiving messages via TCP
      *
      * @return returns whether the connection was established
      */
    def connect(): Boolean = {
        try {
            connection = new Socket()
            connection.connect(new InetSocketAddress(hostName, portNumber), defaultTimeout.toMillis.toInt)
            writer = new PrintWriter(connection.getOutputStream(), true)
            reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))
            true
        }
        catch {
            case e: Exception => {
                Logger.warn(s"Socket not yet open or nobody is listening: ${e.getMessage}", error = e)
                false
            }
        }
    }

    /**
      * The disconnect method handles the orderly closure of a network connection
      */
    def disconnect() = {
        reader.close
        writer.close
        connection.close
    }

    /**
      * The write method handles writing text over a TCP connection to a responding server
      *
      * @param data
      */
    def write(data: String) = {
        try {
            writer.println(data)
        }
        catch {
            case e: Exception => {
                Logger.warn("Unable to perform write to socket.", error = e)
            }
        }
    }

    /**
      * The read method handles responding to messages received from a server over a TCP connection
      * TODO: implementation of the read method for a client
      */
    def read() = {

    }

}
