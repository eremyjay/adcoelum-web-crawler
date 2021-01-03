package com.adcoelum.indexer.property.workers

import akka.actor.Actor
import akka.pattern.ask
import akka.util.Timeout
import com.adcoelum.actors.{AddressInDatabaseCheckResult, Category, ProcessLocation, PropertyCategory, StoreProperty}
import com.adcoelum.common.{Configuration, Logger}
import com.adcoelum.data.{Collection, DatabaseAccessor}
import com.adcoelum.indexer.property.analytics.Processing
import play.api.libs.json._
import reactivemongo.api.DefaultDB

import scala.collection.mutable.{HashMap, ListBuffer}
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.language.postfixOps
import scalaj.http._


/**
  * The Location Processor class examines raw address data and attempts to find a match for the address where possible
  * THe quality of the match is highly dependent on the ability to pick out raw addresses.
  * The class makes use of Photon and Mapzen as its primary and secondary geocoders
  * It can also use nominatim and pelias as well as Google
  */
class LocationProcessor extends Actor {
    implicit val timeout = Timeout(5 seconds)

    // Establish connections to local actors
    val propertyFactoryRouter = context.actorSelection("/user/property_factory_router")

    // Connect to indexers store
    val properties = DatabaseAccessor.collection("properties")

    // Define finders
    val stateFinder = "(?i)(Australian Capital Territory|A[.]?C[.]?T[.]?|New South Wales|N[.]?S[.]?W[.]?|Northern Territory|N[.]?T[.]?|Queensland|Q[.]?L[.]?D[.]?|South Australia|S[.]?A[.]?|Tasmania|T[.]?A[.]?S[.]?|Victoria|V[.]?I[.]?C[.]?|Western Australia|W[.]?A[.])(?-i)"
    val postcodeFinder = "(?i)[ ,]*([0-9]{4,5})(?-i)"
    val numberFinder = ".*[0-9]+.*"

    // Define identifiers
    val dataSourcesIdentifier = "sources"
    val addressIdentifier = "address"
    val rawAddressIdentifier = "raw_address"
    val jsonAddressIdentifier = "address_json"
    val coordsIdentifier = "coordinates"
    val addressIdedIdentifier = "address_identified"
    val addressIdedScoreIdentifier = "address_identified_score"
    val urlIdentifier = "url"
    val space = " "
    val comma = ","
    val slash = "/"
    val geocoderSource = Configuration.geoCoder("photon_source").asInstanceOf[String]

    // Point values
    val duplicateValue = 50
    val stateValue = 50
    val postcodeValue = 25
    val numberValue = 100
    val minimumValidValue = 100

    /**
      * Establish the types of messages that will be handled by the actor
      *
      * @return
      */
    def receive = {
        // Handle processing of the location - commencing with finding if there is an existing property
        case ProcessLocation(crawlMethod, rawDataPoints, rawContent, content, rawAddresses, category, dataIdentifier) => {
            val attributeDataPoints = s"$dataSourcesIdentifier.$dataIdentifier."
            val url = rawDataPoints(attributeDataPoints+urlIdentifier).asInstanceOf[String]
            Logger.trace(s"""Processing location - find any existing: ${url}""")
            findExistingProperty(crawlMethod, rawDataPoints, rawContent, content, rawAddresses, category, dataIdentifier)
        }

        // Process the result of the check of whether the property is already stored
        case AddressInDatabaseCheckResult(crawlMethod, rawDataPoints, rawContent, content, rawAddresses, existingAddress, category, dataIdentifier) => {
            val dataPoints = rawDataPoints
            val prepDataPoints: HashMap[String, Option[Any]] = HashMap.empty[String, Option[Any]]
            val attributeDataPoints = s"$dataSourcesIdentifier.$dataIdentifier."
            val url = rawDataPoints(attributeDataPoints+urlIdentifier).asInstanceOf[String]
            Logger.trace(s"""Processing location - determining actual address: ${url}""")

            var addressTuple: Option[Tuple3[String, Tuple2[String, String], String]] = None
            var address = ""

            // Determine if address already exists and if so, just use that
            if (existingAddress.isDefined) {
                address = existingAddress.get
                prepDataPoints += (addressIdentifier -> Some(address))
            }
            else {
                var definedRawAddress = ""
                var addressesIdentified = ListBuffer.empty[Any]
                var addressesScores = ListBuffer.empty[Any]

                // Set co-ordinates if they have been defined
                val addressCoordinates: Option[String] = {
                    val coords = dataPoints.get(coordsIdentifier)
                    if (coords.isDefined) {
                        val coordsArray = coords.get.asInstanceOf[List[String]]
                        Some(s"${coordsArray(0)}, ${coordsArray{1}}")
                    }
                    else
                        None
                }

                // Try to use raw addresses as a basis
                if (existingAddress.isEmpty && rawAddresses.nonEmpty) {
                    val rankedRawAddresses = rankRawAddresses(crawlMethod, rawAddresses)

                    // Run through each raw address
                    rankedRawAddresses foreach { rw =>
                        addressesIdentified += rw._2
                        addressesScores += rw._4

                        // If the defined raw address does not yet have a value
                        if (definedRawAddress.isEmpty) {
                            // Determine whether the raw address being checked is valid
                            addressTuple = validateAddress(rw, addressIdentifier)

                            // If the validation passes
                            if (addressTuple.isDefined) {
                                // Set the defined raw address
                                definedRawAddress = rw._2
                                prepDataPoints += (attributeDataPoints+rawAddressIdentifier -> Some(definedRawAddress))

                                // Set the address co-ordinates if not already defined
                                if (addressCoordinates.isEmpty) {
                                    val coordsTuple = addressTuple.get._2
                                    prepDataPoints += (coordsIdentifier -> Some(List(coordsTuple._1, coordsTuple._2)))
                                }

                                prepDataPoints += (attributeDataPoints+jsonAddressIdentifier -> Some(addressTuple.get._3))

                                // Set the apartment and address number details
                                if (rw._1 == 1) {
                                    val aptNo = if (rw._3(1) != null) rw._3(1).replace(comma, slash) else ""
                                    val addrNo = if (rw._3(3) != null) rw._3(3) else ""
                                    address = aptNo + addrNo + space + addressTuple.get._1
                                    prepDataPoints += (addressIdentifier -> Some(address))
                                }
                                else if (rw._1 == 2) {
                                    val aptNo = if (rw._3(6) != null) rw._3(6).replace(comma, slash) else ""
                                    val addrNo = if (rw._3(8) != null) rw._3(8) else ""
                                    address = aptNo + addrNo + space + addressTuple.get._1
                                    prepDataPoints += (addressIdentifier -> Some(address))
                                }
                                else {
                                    address = addressTuple.get._1
                                    prepDataPoints += (addressIdentifier -> Some(address))
                                }
                            }
                        }
                    }

                    prepDataPoints += (attributeDataPoints+addressIdedIdentifier -> Some(addressesIdentified))
                    prepDataPoints += (attributeDataPoints+addressIdedScoreIdentifier -> Some(addressesScores))
                }

                // Else Determine address - use co-ordinates if available
                if (existingAddress.isEmpty && definedRawAddress.isEmpty && addressCoordinates.isDefined) {
                    // Attempt to validate the co-ordinates
                    addressTuple = validateAddress((0, addressCoordinates.get, List.empty[String], 0), coordsIdentifier)

                    // If the co-ordinates are validated
                    if (addressTuple.isDefined) {
                        // Set the address data
                        address = addressTuple.get._1
                        prepDataPoints += (attributeDataPoints+rawAddressIdentifier -> Some(addressTuple.get._1))
                        prepDataPoints += (attributeDataPoints+jsonAddressIdentifier -> Some(addressTuple.get._3))
                        prepDataPoints += (addressIdentifier -> Some(address))
                    }
                }
            }

            // Set new data points ready for storage
            prepDataPoints foreach {
                case (key: String, value: Option[Any]) => {
                    if (value.isDefined)
                        dataPoints += (key -> value.get)
                }
            }

            // Create the property - assumes all identified pages are properties - to change in the future
            if (category == PropertyCategory())
                propertyFactoryRouter ! StoreProperty(crawlMethod, address, dataPoints, rawContent, content, dataIdentifier)
        }
    }

    /**
      * This method is used to validate an address using the different forms of validators
      *
      * Other APIs = http://www.geobulk.com
      * https://github.com/alexreisner/geocoder - list of geocoders
      * https://github.com/pelias/pelias
      *
      * @param data
      * @param dataType
      * @param timeout
      * @return
      */
    def validateAddress(data: Tuple4[Int, String, List[String], Int], dataType: String, timeout: Integer = 5000): Option[Tuple3[String, Tuple2[String, String], String]] = {
        var result: Option[Tuple3[String, Tuple2[String, String], String]] = None

        if (data._4 >= minimumValidValue) {
            // Use photon api query as first option
            result = photonAPIQuery(data, dataType, timeout, source = geocoderSource)

            // If not results, attempt to use pelias query, defaults to mapzen if no pelias server defined
            if (result.isEmpty)
                result = peliasAPIQuery(data, dataType, timeout)
        }

        result
    }

    /**
      * This method performs a query to the photon geocoder.  If a source is provided, this will be used otherwise,
      * the default geocoder at photon.komoot.de/api will be used
      *
      * @param data
      * @param dataType
      * @param timeout
      * @param source
      * @return
      */
    def photonAPIQuery(data: Tuple4[Int, String, List[String], Int], dataType: String, timeout: Integer = 5000,
                          source: String = "http://photon.komoot.de/api"): Option[Tuple3[String, Tuple2[String, String], String]] = {
        // Establish initial value (co-ordinates) for data to parse
        var parsedData = data._2

        try {
            val response = dataType match {
                // Case where data is of type address
                case "address" => {
                    val components = ListBuffer.empty[String]

                    // Pull apart components of the address
                    data._3 foreach { part =>
                        components += (if (part != null) part else "")
                    }

                    // Construct the address for parsing based on structure
                    parsedData = Processing.queryAddressConstructor(data._1, components.toList)

                    // Perform query to geocoder
                    Http(source+"/api")
                        .param("limit", "1")
                        .param("osm_value", "residential")
                        .param("q", parsedData)
                        .option(HttpOptions.connTimeout(5000))
                        .option(HttpOptions.readTimeout(5000))
                        .asString
                }
                // Case where data is of type co-ordinates
                case "coordinates" => {
                    parsedData = data._2

                    // Separate the data in to latitude and longitude
                    val latlng = parsedData.split(", ")

                    // Perform query to geocoder
                    Http(source+"/reverse")
                        .param("limit", "1")
                        .param("osm_value", "residential")
                        .param("lon", latlng(0))
                        .param("lat", latlng(1))
                        .option(HttpOptions.connTimeout(5000))
                        .option(HttpOptions.readTimeout(5000))
                        .asString
                }
            }

            // Parse the data from the query result
            Logger.trace(s"Photon API call: ${dataType} >> ${parsedData}")
            val json = Json.parse(response.body)

            // No options found
            if ((json \ "features").as[List[JsValue]].isEmpty)
                return None

            // Construct address details
            val result = (json \ "features")(0)
            val address = (result \ "properties" \ "name").asOpt[String].getOrElse("") + " " +
                (result \ "properties" \ "locality").asOpt[String].getOrElse(
                    (result \ "properties" \ "city").asOpt[String].getOrElse("")
                ) + " " +
                (result \ "properties" \ "state").asOpt[String].getOrElse("") + " " +
                (result \ "properties" \ "country").as[String] + " " +
                (result \ "properties" \ "postcode").asOpt[String].getOrElse("")
            val latitude = (result \ "geometry" \ "coordinates")(1).as[Double]
            val longitude = (result \ "geometry" \ "coordinates")(0).as[Double]
            val coordinates = Tuple2(latitude.toString, longitude.toString)

            // If address details available, return the address
            if (address == "")
                None
            else
                Some((address.trim, coordinates, response.body))
        }
        catch {
            case e: java.net.SocketTimeoutException => {
                Logger.warn(s"Photon Query time out - trying again: ${dataType} >> ${parsedData}", error = e)
                // Try Again - Visit the page and download content
                validateAddress(data, dataType, timeout*2)
            }
            case e: Throwable => {
                Logger.error(s"Photon Query failed for ${parsedData}: ${e.getMessage}", error = e)
                None
            }
        }
    }

    /**
      * This method performs a query to the nominatim geocoder.  If a source is provided, this will be used otherwise,
      * the default geocoder at nominatim.openstreetmap.org will be used
      *
      *
      * @param data
      * @param dataType
      * @param timeout
      * @param source
      * @return
      */
    def nominatimAPIQuery(data: Tuple4[Int, String, List[String], Int], dataType: String, timeout: Integer = 5000,
                          source: String = "http://nominatim.openstreetmap.org"): Option[Tuple3[String, Tuple2[String, String], String]] = {
        // Establish initial value (co-ordinates) for data to parse
        var parsedData = data._2

        try {
            val response = dataType match {
                // Case where data is of type address
                case "address" => {
                    val components = ListBuffer[String]()

                    // Pull apart components of the address
                    data._3 foreach { part =>
                        components += (if (part != null) part else "")
                    }

                    // Construct the address for parsing based on structure
                    parsedData = Processing.queryAddressConstructor(data._1, components.toList)

                    // Perform query to geocoder
                    Http(source+"/search.php").param("api_key", "search-8FLZk1B")
                        .param("countrycodes", "au")
                        .param("limit", "1")
                        .param("addressdetails", "1")
                        .param("extratags", "1")
                        .param("format", "json")
                        .param("q", parsedData)
                        .option(HttpOptions.connTimeout(5000))
                        .option(HttpOptions.readTimeout(5000))
                        .asString
                }
                // Case where data is of type co-ordinates
                case "coordinates" => {
                    parsedData = data._2

                    // Separate the data in to latitude and longitude
                    val latlng = parsedData.split(", ")

                    // Perform query to geocoder
                    Http(source+"/reverse.php").param("api_key", "search-8FLZk1B")
                        .param("countrycodes", "au")
                        .param("limit", "1")
                        .param("addressdetails", "1")
                        .param("extratags", "1")
                        .param("format", "json")
                        .param("lon", latlng(0))
                        .param("lat", latlng(1))
                        .option(HttpOptions.connTimeout(5000))
                        .option(HttpOptions.readTimeout(5000))
                        .asString
                }
            }

            // Parse the data from the query result
            Logger.trace(s"Nominatim API call: ${dataType} >> ${parsedData}")
            val json = Json.parse(response.body)

            // No options found
            if (json.as[List[JsValue]].isEmpty)
                return None

            // Construct address details
            val result = json(0)
            val address = (result \ "address" \ "road").asOpt[String].getOrElse("") + " " +
                (result \ "address" \ "suburb").asOpt[String].getOrElse(
                    (result \ "address" \ "town").asOpt[String].getOrElse(
                        (result \ "address" \ "village").asOpt[String].getOrElse("")
                    )
                ) + " " +
                (result \ "address" \ "state").asOpt[String].getOrElse("") + " " +
                (result \ "address" \ "country").as[String] + " " +
                (result \ "address" \ "postcode").asOpt[String].getOrElse("")
            val latitude = (result \ "lat").as[String]
            val longitude = (result \ "lon").as[String]
            val coordinates = Tuple2(latitude, longitude)

            // If address details available, return the address
            if (address == "")
                None
            else
                Some((address.trim, coordinates, response.body))
        }
        catch {
            case e: java.net.SocketTimeoutException => {
                Logger.warn(s"Nominatim Query time out - trying again: ${dataType} >> ${parsedData}", error = e)
                // Try Again - Visit the page and download content
                validateAddress(data, dataType, timeout*2)
            }
            case e: Throwable => {
                Logger.error(s"Nominatim Query failed for ${parsedData}: ${e.getMessage}", error = e)
                None
            }
        }
    }

    /**
      * This method performs a query to the pelias geocoder.  If a source is provided, this will be used otherwise,
      * the default geocoder at search.mapzen.com/v1 will be used
      *
      * @param data
      * @param dataType
      * @param timeout
      * @param source
      * @return
      */
    def peliasAPIQuery(data: Tuple4[Int, String, List[String], Int], dataType: String, timeout: Integer = 5000,
                       source: String = "https://search.mapzen.com/v1"): Option[Tuple3[String, Tuple2[String, String], String]] = {
        try {
            val response = dataType match {
                // Case where data is of type address
                case "address" => {
                    Http(source+"/search").param("api_key", "search-8FLZk1B")
                        .param("boundary.country", "au")
                        .param("size", "1")
                        .param("layers", "address")
                        .param("text", data._2)
                        .option(HttpOptions.connTimeout(5000))
                        .option(HttpOptions.readTimeout(5000))
                        .asString
                }
                // Case where data is of type co-ordinates
                case "coordinates" => {
                    // Separate the data in to latitude and longitude
                    val latlng = data._2.split(", ")

                    // Perform query to geocoder
                    Http(source+"/reverse").param("api_key", "search-8FLZk1B")
                        .param("boundary.country", "au")
                        .param("size", "1")
                        .param("layers", "address")
                        .param("point.lon", latlng(0))
                        .param("point.lat", latlng(1))
                        .option(HttpOptions.connTimeout(5000))
                        .option(HttpOptions.readTimeout(5000))
                        .asString
                }
            }

            // Parse the data from the query result
            Logger.trace(s"Pelias API call: ${dataType} >> ${data._2}")
            val json = Json.parse(response.body)

            // No options found
            if ((json \ "features").as[List[JsValue]].isEmpty)
                return None

            // Pull the result
            val result = (json \ "features")(0)

            // Non-valid address found where confidence is low
            val confidence = (result \ "properties" \ "confidence").as[Double]
            if (confidence <= 0.4) {
                Logger.warn(s"Mapzen query confidence low: ${confidence} for ${dataType} >> ${data}", error = new Throwable)
                return None
            }

            // Construct address details
            val address = (result \ "properties" \ "street").asOpt[String].getOrElse("") + " " +
                            (result \ "properties" \ "locality").asOpt[String].getOrElse(
                                (result \ "properties" \ "localadmin").asOpt[String].getOrElse("")
                            ) + " " +
                            (result \ "properties" \ "region").asOpt[String].getOrElse("") + " " +
                            (result \ "properties" \ "country").as[String] + " " +
                            (result \ "properties" \ "postalcode").asOpt[String].getOrElse("")
            val longitude = (result \ "geometry" \ "coordinates")(0).as[Double]
            val latitude = (result \ "geometry" \ "coordinates")(1).as[Double]
            val coordinates = Tuple2(latitude.toString, longitude.toString)

            // If address details available, return the address
            if (address == "")
                None
            else
                Some((address.trim, coordinates, response.body))
        }
        catch {
            case e: java.net.SocketTimeoutException => {
                Logger.warn(s"Mapzen Query time out - trying again: ${dataType} >> ${data._2}", error = e)
                // Try Again - Visit the page and download content
                validateAddress(data, dataType, timeout*2)
            }
        }
    }

    /**
      * This method performs a query to the Google Maps API geocoder.
      *
      * @param data
      * @param dataType
      * @param timeout
      * @return
      */
    def googleMapsAPIQuery(data: Tuple4[Int, String, List[String], Int], dataType: String, timeout: Integer = 5000): Option[Tuple3[String, Tuple2[String, String], String]] = {
        try {
            // Perform a mapping for search type
            val searchType = dataType match {
                case "address" => "address"
                case "coordinates" => "latlng"
            }

            // Perform query to geocoder
            val response = Http("https://maps.googleapis.com/maps/api/geocode/json")
                .param(searchType, data._2)
                .param("region", "au")
                .option(HttpOptions.connTimeout(5000))
                .option(HttpOptions.readTimeout(5000))
                .asString

            // Parse the data from the query result
            Logger.trace(s"Google Maps API call: ${dataType} >> ${data}")
            val json = Json.parse(response.body)

            // Non-valid address found
            val status = (json \ "status").as[String]
            if (status != "OK") {
                Logger.warn(s"Google query failure: ${status} for ${dataType} >> ${data}", error = new Throwable)
                return None
            }

            // Construct address details
            val address = ((json \ "results")(0) \ "formatted_address").as[String]
            val latitude = ((json \ "results")(0) \ "geometry" \ "location" \ "lat").as[Double]
            val longitude = ((json \ "results")(0) \ "geometry" \ "location" \ "lng").as[Double]
            val coordinates = Tuple2(latitude.toString, longitude.toString)

            // If address details available, return the address
            if (address == "")
                None
            else
                Some((address.trim, coordinates, response.body))

            /* Google case
                "ROOFTOP" indicates that the returned result is a precise geocode for which we have location information accurate down to street address precision.
                "RANGE_INTERPOLATED" indicates that the returned result reflects an approximation (usually on a road) interpolated between two precise points (such as intersections). Interpolated results are generally returned when rooftop geocodes are unavailable for a street address.
                "GEOMETRIC_CENTER" indicates that the returned result is the geometric center of a result such as a polyline (for example, a street) or polygon (region).
                "APPROXIMATE" indicates that the returned result is approximate.
            // Check if match is of reasonable quality
            val quality = ((Json.parse(address.get._3) \ "results")(0) \ "geometry" \ "location_type").as[String]
            if (quality == "GEOMETRIC_CENTER" || quality == "APPROXIMATE") {
                val aptNo = if (rw._2(1) != null) rw._2(1) else ""
                val addrNo = if (rw._2(3) != null) rw._2(3) else ""

                prepDataPoints += ("address" -> Some(aptNo + addrNo + " " + address.get._1))
            }
            else
                prepDataPoints += ("address" -> Some(address.get._1))
            */

        }
        catch {
            case e: java.net.SocketTimeoutException => {
                Logger.warn(s"Google Maps Query time out - trying again: ${dataType} >> ${data}", error = e)
                // Try Again - Visit the page and download content
                validateAddress(data, dataType, timeout*2)
            }
        }
    }


    /**
      * This method is used to determine whether a property has been previously found and if it can be matched against
      * a property already in the collection.  It will return the results of its attempt to find a match.
      *
      * @param rawDataPoints
      * @param rawContent
      * @param content
      * @param rawAddresses
      * @param category
      * @return
      */
    def findExistingProperty(crawlMethod: Int, rawDataPoints: HashMap[String, Any], rawContent: String, content: String, rawAddresses: List[Tuple3[Int, String, List[String]]], category: Category, dataIdentifier: String) = {
        // Try to find any of the identifying components
        try {
            var rawAddressList = ListBuffer.empty[String]
            rawAddresses foreach { rawAddress =>
                rawAddressList += rawAddress._2
            }

            // Check to see if URL is available
            val attributeDataPoints = s"$dataSourcesIdentifier.$dataIdentifier."
            val url = rawDataPoints(attributeDataPoints+urlIdentifier).asInstanceOf[String]
            val query = Map("$or" -> List(
                Map(urlIdentifier -> Map("$in" -> List(url))),
                Map("$and" -> List(
                    Map(rawAddressIdentifier -> Map("$in" -> rawAddressList)),
                    Map(coordsIdentifier -> rawDataPoints.get(coordsIdentifier).asInstanceOf[Option[List[String]]])
                ))
            ))

            // Define results to provide
            val projection = Map(addressIdentifier -> "")

            // Action results based on the search
            def action(result: Map[String, Any]) = {
                if (result.isEmpty)
                    self ! AddressInDatabaseCheckResult(crawlMethod, rawDataPoints, rawContent, content, rawAddresses, None, category, dataIdentifier)
                else
                    self ! AddressInDatabaseCheckResult(crawlMethod, rawDataPoints, rawContent, content, rawAddresses, Some(result.get("address").asInstanceOf[String]), category, dataIdentifier)
            }

            properties.readFirst(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Find existing failure: ${e.getMessage}", error = e)
                None
        }
    }

    def rankRawAddresses(crawlMethod: Int, rawAddresses: List[Tuple3[Int, String, List[String]]]): List[Tuple4[Int, String, List[String], Int]] = {
        val rankedAddresses: ListBuffer[Tuple4[Int, String, List[String], Int]] = ListBuffer.empty
        val unrankedAddresses: ListBuffer[Tuple4[Int, String, List[String], Int]] = ListBuffer.empty

        rawAddresses foreach { rw =>
            val selector = rw._1
            val rawAddress = rw._2
            var alreadyFound = false

            var counter = 0
            unrankedAddresses foreach { unranked =>
                if (rawAddress == unranked._2 && selector == unranked._1) {
                    alreadyFound = true
                    unrankedAddresses(counter) = Tuple4(selector, unrankedAddresses(counter)._2, unrankedAddresses(counter)._3, unrankedAddresses(counter)._4 + duplicateValue)
                }

                counter += 1
            }

            if (!alreadyFound) {
                var points = 0

                if ((stateFinder.r findFirstIn rawAddress).isDefined)
                    points += stateValue
                if ((postcodeFinder.r findFirstIn rawAddress).isDefined)
                    points += postcodeValue
                rw._1 match {
                    case 1 => {
                        if (rw._3(1) != null && rw._3(1).matches(numberFinder))
                            points += numberValue
                        else if (rw._3(3) != null && rw._3(3).matches(numberFinder))
                            points += numberValue
                    }
                    case 2 => {
                        if (rw._3(6) != null && rw._3(6).matches(numberFinder))
                            points += numberValue
                        else if (rw._3(8) != null && rw._3(8).matches(numberFinder))
                            points += numberValue
                    }
                    case _ => {}
                }

                unrankedAddresses += Tuple4(rw._1, rw._2, rw._3, points)
            }
        }

        unrankedAddresses foreach { unrankedTuple =>
            if (rankedAddresses.isEmpty)
                rankedAddresses append unrankedTuple
            else {
                var counter = 0
                var inserted = false
                rankedAddresses foreach { rankedTuple =>
                    if (!inserted) {
                        if (unrankedTuple._4 >= rankedTuple._4) {
                            rankedAddresses.insert(counter, unrankedTuple)
                            inserted = true
                        }
                    }

                    counter += 1
                }

                if (!inserted)
                    rankedAddresses append unrankedTuple
            }
        }

        rankedAddresses.toList
    }
}