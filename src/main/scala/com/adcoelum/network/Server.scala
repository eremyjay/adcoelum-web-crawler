package com.adcoelum.network

import java.io.{BufferedReader, DataOutputStream, InputStreamReader}
import java.net._

import com.adcoelum.common.{Configuration, Logger}


/**
  * The server class handles network connections acting as a server receiving connections from clients over TCP.
  * The class handles the basic java network layer configuration and provides a programmatic interface for connections.
  *
  */
class Server(callbackFunc: Function1[String, Any], port: Integer = Configuration.responderPort, bindAddress: String = Configuration.responderAddress) {

    // Establish the variables necessary to manage connections
    var listening = false
    var listenThread: Thread = _
    var socket: ServerSocket = _
    var callback: Function1[String, Any] = callbackFunc

    /**
      * The start method brings the server up and ready for listening on a port for new connections
      */
    def start = {
        if (!listening) {
            listening = true
            listenThread = new Thread(new ListenThread)
            listenThread.start
        }
    }

    /**
      * The stop method handles the orderly closure of the network listener
      */
    def stop = {
        if (listening) {
            listening = false
            socket.close
        }
    }

    /**
      * The read method handles new connections to the server and pushes the connection to a thread for further handling
      */
    def read() = {
        try {
            val connection: Socket = socket.accept
            val process = new Thread(new ProcessThread(connection))
            process.start
        }
        catch {
            case e: Exception => {
                Logger.warn(s"Socket no longer able to accept connections: ${e.getMessage}", error = e)
            }
        }
    }

    /**
      * The register callback function provides the ability to call a function on completion of an action, in this case
      * following the successful reading of some data
      *
      * @param function
      */
    def registerCallback(function: Function1[String, Any]) = {
        callback = function
    }

    /**
      * The write method allows for the server to write back data to a client on a connection
      *
      * @param data
      * @param connection
      * @return success of write
      */
    def write(data: String, connection: Socket): Boolean = {
        try {
            val dataOutput = new DataOutputStream(connection.getOutputStream)
            dataOutput.writeBytes(data)
            true
        }
        catch {
            case e: Exception => {
                Logger.warn(s"Unable to write to socket connection: ${e.getMessage}", error = e)
                false
            }
        }
    }

    /**
      * The get port methods returns the port on which the server is configured for listening
      *
      * @return
      */
    def getPort = {
        port
    }

    /**
      * Determine whether the server is listening
      *
      * @return
      */
    def isListening = {
        listening
    }

    /**
      * The listen thread class provides a thread for listening separate to the spawning class, creating a server socket
      * on a specified port and binding address
      */
    class ListenThread extends Runnable {
        /**
          * The run method implements the listening thread
          */
        def run = {
            try {
                socket = new ServerSocket(port, 50, InetAddress.getByName(bindAddress))

                while (listening && !socket.isClosed) {
                    read
                }
            }
            catch {
                case e: Exception => {
                    Logger.error(s"Unexpected error with socket: ${e.getMessage}", error = e)
                    listening = false
                }
            }
        }
    }

    /**
      * The process thread class is responsible for processing data received from a client on a connection, potentially
      * processing a callback method on the data received and responding where the result of the callback methods
      * provides some data
      *
      * @param connection
      */
    class ProcessThread(connection: Socket) extends Runnable {
        /**
          * The run method implements the process thread
          */
        def run = {
            try {
                val dataInput: BufferedReader = new BufferedReader(new InputStreamReader(connection.getInputStream))

                    Iterator.continually(dataInput.readLine).takeWhile(_ != null).foreach(instruction => {
                        val result = callback(instruction)

                        if (result.isInstanceOf[String]) {
                            val writeSuccess = write(result.asInstanceOf[String], connection)
                            if (!writeSuccess)
                                throw new Exception("Failed to perform write successfully")
                        }
                    })

                dataInput.close
            }
            catch {
                case e: Exception => {
                    Logger.warn(s"Connection has been closed prematurely: ${e.getMessage}", error = e)
                }
            }
        }
    }
}

