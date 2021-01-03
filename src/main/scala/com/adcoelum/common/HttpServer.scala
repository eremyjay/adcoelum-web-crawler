package com.adcoelum.common

import spark._

/**
 * Spark for Scala
 *
 * Copyright 2015 Dennis Vriend
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
class HttpServer(address: String, port: Int) {
    val server = Service.ignite

    server.ipAddress(address)
    server.port(port)

    var listening = true


    /**
      *
      * @param path
      * @param f
      * @return
      */
    def convertToRequest(path: String, f: (Request, Response) ⇒ AnyRef): Route = {
        new Route() {
            override def handle(request: Request, response: Response): AnyRef = {
                f(request, response)
            }
        }
    }

    /**
      *
      * @param path
      * @param f
      * @return
      */
    def convertToFilter(path: String, f: (Request, Response) ⇒ Unit): Filter = {
        new Filter() {
            override def handle(request: Request, response: Response): Unit = {
                f(request, response)
            }
        }
    }

    /**
      *
      * @param path
      * @param f
      */
    def get(path: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.get(path, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param f
      */
    def post(path: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.post(path, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param f
      */
    def put(path: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.put(path, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param f
      */
    def patch(path: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.patch(path, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param f
      */
    def delete(path: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.delete(path, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param f
      */
    def head(path: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.head(path, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param f
      */
    def trace(path: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.trace(path, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param f
      */
    def connect(path: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.connect(path, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param f
      */
    def options(path: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.options(path, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param f
      */
    def before(path: String)(implicit f: (Request, Response) ⇒ Unit): Unit = {
        server.before(path, convertToFilter(path, f))
    }

    /**
      *
      * @param path
      * @param f
      */
    def after(path: String)(implicit f: (Request, Response) ⇒ Unit): Unit = {
        server.after(path, convertToFilter(path, f))
    }

    /**
      *
      * @param path
      * @param acceptType
      * @param f
      */
    def get(path: String, acceptType: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.get(path, acceptType, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param acceptType
      * @param f
      */
    def post(path: String, acceptType: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.post(path, acceptType, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param acceptType
      * @param f
      */
    def put(path: String, acceptType: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.put(path, acceptType, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param acceptType
      * @param f
      */
    def patch(path: String, acceptType: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.patch(path, acceptType, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param acceptType
      * @param f
      */
    def delete(path: String, acceptType: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.delete(path, acceptType, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param acceptType
      * @param f
      */
    def head(path: String, acceptType: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.head(path, acceptType, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param acceptType
      * @param f
      */
    def trace(path: String, acceptType: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.trace(path, acceptType, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param acceptType
      * @param f
      */
    def connect(path: String, acceptType: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.connect(path, acceptType, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param acceptType
      * @param f
      */
    def options(path: String, acceptType: String)(implicit f: (Request, Response) ⇒ AnyRef): Unit = {
        server.options(path, acceptType, convertToRequest(path, f))
    }

    /**
      *
      * @param path
      * @param acceptType
      * @param f
      */
    def before(path: String, acceptType: String)(implicit f: (Request, Response) ⇒ Unit): Unit = {
        server.before(path, acceptType, convertToFilter(path, f))
    }

    /**
      *
      * @param path
      * @param acceptType
      * @param f
      */
    def after(path: String, acceptType: String)(implicit f: (Request, Response) ⇒ Unit): Unit = {
        server.after(path, acceptType, convertToFilter(path, f))
    }

    def getPort: Int = {
        port
    }

    def getAddress: String = {
        address
    }

    def stop: Unit = {
        listening = false
        server.stop
    }

    def start: Unit = {
        server.init
        listening = true
    }

    def isListening: Boolean = {
        listening
    }
}
