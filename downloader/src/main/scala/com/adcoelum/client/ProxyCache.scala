package com.adcoelum.client

import java.net.URL

import com.adcoelum.common.Configuration
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http._

import scala.collection.mutable.HashMap

/**
  * The Proxy Cache object which holds all items that are being cached for use by the proxy
  */
object ProxyCache {
    // Establish the cache and maximum size
    val cache: HashMap[String, FullHttpResponse] = HashMap.empty
    val cacheSize = (Configuration.config \ "downloader" \ "proxy" \ "cacheSize").as[Int]

    /**
      * Determine whether a particular Url is already cached
      *
      * @param url
      * @return
      */
    def isCached(url: String): Boolean = {
        cache.contains(url)
    }

    /**
      * Cache a particular Url item, drop the oldest item from the cache if full
      *
      * @param urlString
      * @param response
      * @return
      */
    def cacheUrl(urlString: String, response: HttpResponse) = {
        // Establish the Url and filename details
        val url = new URL(urlString)
        val fileSplit = url.getPath.split("/")
        val filename = if (fileSplit.length > 1) fileSplit.last else ""

        // If the cache is full, remove the oldest item from the cache
        if (cache.size > cacheSize)
            cache.remove(cache.iterator.next._1)

        // Obtain the data from the response and insert into data object
        val data = new Array[Byte](response.asInstanceOf[FullHttpResponse].content.capacity)
        response.asInstanceOf[FullHttpResponse].content.getBytes(0, data)

        // Create cacheable item based on the data returned
        val cacheableItem = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(data))
        cacheableItem.headers.set("Content-Type", response.headers.get("Content-Type"))
        cacheableItem.headers.set("Date", response.headers.get("Date"))
        cacheableItem.headers.set("Connection", "close")

        cache += (filename -> cacheableItem)
    }

    /**
      * Obtain a copy of the cached item
      *
      * @param url
      * @return
      */
    def getObject(url: String): FullHttpResponse = {
        cache(url).copy
    }
}

