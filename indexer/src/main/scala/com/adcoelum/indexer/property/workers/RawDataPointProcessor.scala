package com.adcoelum.indexer.property.workers

import java.net.URL
import java.nio.file.{Files, Paths, StandardOpenOption}

import akka.actor.Actor
import com.adcoelum.common.{Configuration, Logger, ScoringEngine, Tools}
import com.adcoelum.actors._
import com.adcoelum.indexer.property.analytics.Processing

import scala.collection.mutable.{HashMap, ListBuffer}
import scala.language.postfixOps
import scala.xml._



/**
  * This class plays a critical role in pulling out key pieces of data from a page that can be used
  * to define the attributes of a property, including the address, number of bedrooms and even energy rating
  * This class is a critical building block to understanding a listing, following classification and may
  * even provide a hint as to whether a false positive has been recognised by the classifier
  *
  */
class RawDataPointProcessor extends Actor {
	// Establish connections to local actors
	val locationProcessRouter = context.actorSelection("/user/location_process_router")
	val propertyFactoryRouter = context.actorSelection("/user/property_factory_router")

	// Define hashing method
	val hashMethod = "SHA-1"
	val digest = java.security.MessageDigest.getInstance(hashMethod)

	// Define identifiers
	val stringType = "UTF-8"
	val stringFormatter = "%02x"
	val dataSourcesIdentifier = "sources"
	val urlIdentifier = "url"
	val updateIdentifier = "last_update"
	val rawContentIdentifier = "raw_content"
	val metaTextIdentifier = "meta_text"
	val propertyIdIdentifier = "property_id"
	val statusIdentifier = "status"
	val typeIdentifier = "type"
	val bedroomsIdentifier = "bedrooms"
	val bathroomsIdentifier = "bathrooms"
	val toiletsIdentifier = "toilets"
	val energyIdentifier = "energy_rating"
	val carSpacesIdentifier = "car_spaces"
	val waterRatesIdentifier = "water_rates"
	val councilRatesIdentifier = "council_rates"
	val strataRatesIdentifier = "strata_rates"
	val rentIdentifier = "rent"
	val areaIdentifier = "area"
	val priceIdentifier = "price"
	val bondIdentifier = "bond"
	val auctionDateIdentifier = "auction_date"
	val inspectionDatesIdentifier = "inspection_dates"
	val coordinatesIdentifier = "coordinates"
	val queryIdentifier = "?"


	// Create various finders
	// TODO: Several types of attributes still to implement from raw data: Frontage, Depth, Living Area, Ensuite
	private val number = "([0-9]{1,3},([0-9]{3},)*[0-9]{3}|[0-9]+)([.][0-9][0-9])?".r
	private val integer = "([0-9]{1,3},([0-9]{3},)*[0-9]{3}|[0-9]+)"
	private val price = "[$]([0-9]{1,3},([0-9]{3},)*[0-9]{3}|[0-9]+)([.][0-9][0-9])?".r
	private val priceRange = s"($price([ ]*?([-]|to)[ ]*?$price)??)(?-i)".r


	val typeFinder = List("(?i)[ ,._](apartment|cabin|bungalow|castle|chalet|condo|condominium|cottage|council house|detached house|duplex|house|loft|manor|mansion|quadruplex|triplex|queenslander|semi-detached|studio|townhouse|terrace|unit|villa|warehouse)[ ,._](?-i)",
		"(?i)[ ,._](residence|abode|home|land|flat)[ ,._](?-i)")
	val bedFinder = List(s"(?i)bed[a-z]*[ :-]+[ _]*$integer(?-i)",
		s"(?i)$integer[ _]+([a-z-.]*[ ]*){0,2}(bed[s]??|bedroom[s]??)[ _](?-i)")
	val bathFinder = List(s"(?i)bath[a-z]*[ :-]+[ _]*$integer(?-i)",
		s"(?i)$integer[ _]+([a-z-.]*[ ]*){0,2}(bath[s]??|bathroom[s]??)[ _](?-i)")
	val toiletFinder = List(s"(?i)toilet[a-z]*[ :-]+[ _]*$integer(?-i)",
		s"(?i)$integer[ _]+([a-z-.]*[ ]*){0,2}(toilet[s]??)[ _](?-i)")
	val energyFinder = List(s"(?i)energy[a-z]*[ :-]+[ _]*$number(?-i)")
	val carFinder = List(s"(?i)car[a-z]*[ :-]+[ _]*$integer(?-i)",
		s"(?i)garage[ ]?[a-z]*[ :-]+[ _]*$integer(?-i)",
		s"(?i)$integer[ _]+([a-z-.]*[ ]*){0,2}(car[s]??|carspace[s]??)[ _](?-i)")
	val waterRateFinder = List(s"(?i)water[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*p[qam.]+)[ ](?-i)",
		s"(?i)water[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*per[ ]*[qam][a-z. ]+[ ])(?-i)",
		s"(?i)water[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*[qam][a-z. ]+[ ])(?-i)",
		s"(?i)water[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*[/][ ]*[a-z.]+[ ])(?-i)")
	val councilRateFinder = List(s"(?i)council[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*p[qam.]+)[ ](?-i)",
		s"(?i)council[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*per[ ]*[qam][a-z. ]+[ ])(?-i)",
		s"(?i)council[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*[qam][a-z. ]+[ ])(?-i)",
		s"(?i)council[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*[/][ ]*[a-z.]+[ ])(?-i)")
	val strataLevyFinder = List(s"(?i)(strata|admin|sinking|levies)[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*p[qam.]+)[ ](?-i)",
		s"(?i)(strata|admin|sinking|levies)[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*per[ ]*[qam][a-z. ]+[ ])(?-i)",
		s"(?i)(strata|admin|sinking|levies)[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*[qam][a-z. ]+[ ])(?-i)",
		s"(?i)(strata|admin|sinking|levies)[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*[/][ ]*[a-z.]+[ ])(?-i)")
	val rentFinder = List(s"(?i)rent[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*p[qam.]+)[ ](?-i)",
		s"(?i)rent[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*per[ ]*[qam][a-z. ]+[ ])(?-i)",
		s"(?i)rent[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*[qam][a-z. ]+[ ])(?-i)",
		s"(?i)rent[a-z ]*[ :-]+[a-z-.]*[ ]*($price[ ]*[/][ ]*[a-z.]+[ ])(?-i)")
	val bondFinder = List(s"(?i)bond[a-z ]*[a-z-. ]*[ :-]*[_]?($price)(?-i)")
	val priceFinder = List("(?i)(price[a-z]*|guid[a-z]*|sale|sold|auction[a-z]*|rang[a-z]*|offer[a-z]*)??"+
		s"([ _]+?[a-z]+?){0,2}[ :_-]+?($price([ ]*?([-]|to)[ ]*?$price)??)[ ]*[_](?-i)")
	val statusFinder = List("(?i)(for|on|to)[ ]+(auction|rent|lease|sale|private treaty)([ ]+(for|on))?(?-i)",
		"(?i)()(sold|auctioned|leased|rented)[ ]+(for|on|to)?(?-i)",
		"(?i)()(under contract|under offer)(?-)")
	val idFinder = List("(?i)(property id[ :-]+)([0-9a-z]+)(?-i)",
		"(?i)(property no[.]?[ :-]+)([0-9a-z]+)(?-i)",
		"(?i)(id[ :-]+)([0-9a-z]+)(?-i)")
	val areaFinder = List("(?i)([0-9,.]+[ ]*sq[a-z]*[ ]*m[a-z]*)[ _](?-i)",
		"(?i)([0-9,.]+[ ]*hec[a-z]*[ ]*)[ _](?-i)")
	val addressFinder = List(
		"[ _]+(Apt|Apartment|Fy|Factory|F|Flat|L|Lot|Mb|Marine Berth|Off|Office|Rm|Room|Shed|Shop|Site|Se|Ste|Suite|U|Unit|Vlla|Villa|We|Warehouse|B|Basement|Fl|Floor)?[. ]*?"+
		"([A-Z]*?[0-9]+?[-0-9]*?[a-z]*?[ ]*?[,/ ]+?)?"+
		"([ ]*?Number|No)?[. ]*?([A-Z]*?[0-9]+?[-0-9]*?[A-Z]*?)"+
		"[ ,]+(([A-Z]['a-zA-Z-]+[ ]?){1,3})[ ](Alley|Al|Amble|Amb|Approach|Appr|Arcade|Arc|Arterial|Art|Avenue|Av|Ave|Bay|Bay|Bend|Bend|Brae|Brae|Break|Brk|Boulevard|Bvd|Boardwalk|Bwk|Bowl|Bwl|Bypass|Byp|Circle|Ccl|Circus|Ccs|Circuit|Cct|Chase|Cha|Close|Cl|Corner|Cnr|Common|Com|Concourse|Con|Crescent|Cr|Cres|Cross|Cros|Course|Crse|Crest|Crst|Cruiseway|Cry|Court|Ct|Cove|Cv|Dale|Dale|Dell|Dell|Dene|Dene|Divide|Div|Domain|Dom|Drive|Dr|East|East|Edge|Edg|Entrance|Ent|Esplanade|Esp|Extension|Extn|Flats|Flts|Ford|Ford|Freeway|Fwy|Gate|Gate|Gardens|Garden|Gdn|Glade|Glades|Gla|Glen|Gln|Gully|Gly|Grange|Gra|Green|Grn|Grove|Gv|Gateway|Gwy|Hill|Hill|Hollow|Hlw|Heath|Hth|Heights|Hts|Hub|Hub|Highway|Hwy|Island|Id|Junction|Jct|Kingsway|Lane|La|Link|Lnk|Loop|Loop|Lower|Lwr|Laneway|Lwy|Mall|Mall|Mew|Mew|Mews|Mws|Nook|Nook|North|Nth|Outlook|Out|Path|Path|Parade|Pd|Pde|Pocket|Pkt|Parkway|Pkw|Place|Pl|Plaza|Plz|Promenade|Prm|Pass|Ps|Passage|Psg|Point|Pt|Pursuit|Pur|Pathway|Pway|Quadrant|Qd|Quay|Qu|Reach|Rch|Road|Rd|Roadway|Rwy|Ridge|Rdg|Reserve|Rest|Rest|Rest|Retreat|Ret|Ride|Ride|Rise|Rise|Round|Rnd|Row|Row|Rising|Rsg|Return|Rtn|Run|Run|Slope|Slo|Square|Sq|Street|St|Strait|South|Sth|Strip|Stp|Steps|Stps|Subway|Sub|Terrace|Tce|Throughway|Thru|Tor|Tor|Track|Trk|Trail|Trl|Turn|Turn|Tollway|Twy|Upper|Upr|Valley|Vly|Vista|Vst|View|Vw|Way|Way|Wood|Wd|West|West|Walk|Wk|Walkway|Wkwy|Waters|Wtrs|Waterway|Wry|Wynd|Wyd)[.]??"+
		"[ ,_]+(([A-Z]['a-zA-Z-]+[ ]?){1,10})" +
		"[ ]*([0-9]{4,5})?" +
		"[ ,]+(Australian Capital Territory|A[.]?C[.]?T[.]?|New South Wales|N[.]?S[.]?W[.]?|Northern Territory|N[.]?T[.]?|Queensland|Q[.]?L[.]?D[.]?|South Australia|S[.]?A[.]?|Tasmania|T[.]?A[.]?S[.]?|Victoria|V[.]?I[.]?C[.]?|Western Australia|W[.]?A[.]?)?"+
		"[ ]*([0-9]{4,5})?", // Australian Address
		"[ _]+(([A-Z]['a-zA-Z-]+[ ]?){1,10})" +
		"[ ]*([0-9]{4,5})?" +
		"[ ,]*(Australian Capital Territory|A[.]?C[.]?T[.]?|New South Wales|N[.]?S[.]?W[.]?|Northern Territory|N[.]?T[.]?|Queensland|Q[.]?L[.]?D[.]?|South Australia|S[.]?A[.]?|Tasmania|T[.]?A[.]?S[.]?|Victoria|V[.]?I[.]?C[.]?|Western Australia|W[.]?A[.]?)?"+
		"[ ]*([0-9]{4,5})?" +
		"[ ]*[,][ ]*(Apt|Apartment|Fy|Factory|F|Flat|L|Lot|Mb|Marine Berth|Off|Office|Rm|Room|Shed|Shop|Site|Se|Ste|Suite|U|Unit|Vlla|Villa|We|Warehouse|B|Basement|Fl|Floor)?[. ]*?"+
		"([A-Z]*?[0-9]+?[-0-9]*?[a-z]*?[ ]*?[,/ ]+?)?"+
		"([ ]*?Number|No)?[. ]*?([A-Z]*?[0-9]+?[-0-9]*?[A-Z]*?)"+
		"[ ]+(([A-Z]['a-zA-Z-]+[ ]?){1,3})[ ](Alley|Al|Amble|Amb|Approach|Appr|Arcade|Arc|Arterial|Art|Avenue|Av|Ave|Bay|Bay|Bend|Bend|Brae|Brae|Break|Brk|Boulevard|Bvd|Boardwalk|Bwk|Bowl|Bwl|Bypass|Byp|Circle|Ccl|Circus|Ccs|Circuit|Cct|Chase|Cha|Close|Cl|Corner|Cnr|Common|Com|Concourse|Con|Crescent|Cr|Cres|Cross|Cros|Course|Crse|Crest|Crst|Cruiseway|Cry|Court|Ct|Cove|Cv|Dale|Dale|Dell|Dell|Dene|Dene|Divide|Div|Domain|Dom|Drive|Dr|East|East|Edge|Edg|Entrance|Ent|Esplanade|Esp|Extension|Extn|Flats|Flts|Ford|Ford|Freeway|Fwy|Gate|Gate|Gardens|Garden|Gdn|Glade|Glades|Gla|Glen|Gln|Gully|Gly|Grange|Gra|Green|Grn|Grove|Gv|Gateway|Gwy|Hill|Hill|Hollow|Hlw|Heath|Hth|Heights|Hts|Hub|Hub|Highway|Hwy|Island|Id|Junction|Jct|Kingsway|Lane|La|Link|Lnk|Loop|Loop|Lower|Lwr|Laneway|Lwy|Mall|Mall|Mew|Mew|Mews|Mws|Nook|Nook|North|Nth|Outlook|Out|Path|Path|Parade|Pd|Pde|Pocket|Pkt|Parkway|Pkw|Place|Pl|Plaza|Plz|Promenade|Prm|Pass|Ps|Passage|Psg|Point|Pt|Pursuit|Pur|Pathway|Pway|Quadrant|Qd|Quay|Qu|Reach|Rch|Road|Rd|Roadway|Rwy|Ridge|Rdg|Reserve|Rest|Rest|Rest|Retreat|Ret|Ride|Ride|Rise|Rise|Round|Rnd|Row|Row|Rising|Rsg|Return|Rtn|Run|Run|Slope|Slo|Square|Sq|Street|St|Strait|South|Sth|Strip|Stp|Steps|Stps|Subway|Sub|Terrace|Tce|Throughway|Thru|Tor|Tor|Track|Trk|Trail|Trl|Turn|Turn|Tollway|Twy|Upper|Upr|Valley|Vly|Vista|Vst|View|Vw|Way|Way|Wood|Wd|West|West|Walk|Wk|Walkway|Wkwy|Waters|Wtrs|Waterway|Wry|Wynd|Wyd)[.]??",
		// Australian Address with suburb and state first
		"[ _]+(([A-Z]['a-zA-Z-]+[ ]?){1,3})[ ](Alley|Al|Amble|Amb|Approach|Appr|Arcade|Arc|Arterial|Art|Avenue|Av|Ave|Bay|Bay|Bend|Bend|Brae|Brae|Break|Brk|Boulevard|Bvd|Boardwalk|Bwk|Bowl|Bwl|Bypass|Byp|Circle|Ccl|Circus|Ccs|Circuit|Cct|Chase|Cha|Close|Cl|Corner|Cnr|Common|Com|Concourse|Con|Crescent|Cr|Cres|Cross|Cros|Course|Crse|Crest|Crst|Cruiseway|Cry|Court|Ct|Cove|Cv|Dale|Dale|Dell|Dell|Dene|Dene|Divide|Div|Domain|Dom|Drive|Dr|East|East|Edge|Edg|Entrance|Ent|Esplanade|Esp|Extension|Extn|Flats|Flts|Ford|Ford|Freeway|Fwy|Gate|Gate|Gardens|Garden|Gdn|Glade|Glades|Gla|Glen|Gln|Gully|Gly|Grange|Gra|Green|Grn|Grove|Gv|Gateway|Gwy|Hill|Hill|Hollow|Hlw|Heath|Hth|Heights|Hts|Hub|Hub|Highway|Hwy|Island|Id|Junction|Jct|Kingsway|Lane|La|Link|Lnk|Loop|Loop|Lower|Lwr|Laneway|Lwy|Mall|Mall|Mew|Mew|Mews|Mws|Nook|Nook|North|Nth|Outlook|Out|Path|Path|Parade|Pd|Pde|Pocket|Pkt|Parkway|Pkw|Place|Pl|Plaza|Plz|Promenade|Prm|Pass|Ps|Passage|Psg|Point|Pt|Pursuit|Pur|Pathway|Pway|Quadrant|Qd|Quay|Qu|Reach|Rch|Road|Rd|Roadway|Rwy|Ridge|Rdg|Reserve|Rest|Rest|Rest|Retreat|Ret|Ride|Ride|Rise|Rise|Round|Rnd|Row|Row|Rising|Rsg|Return|Rtn|Run|Run|Slope|Slo|Square|Sq|Street|St|Strait|South|Sth|Strip|Stp|Steps|Stps|Subway|Sub|Terrace|Tce|Throughway|Thru|Tor|Tor|Track|Trk|Trail|Trl|Turn|Turn|Tollway|Twy|Upper|Upr|Valley|Vly|Vista|Vst|View|Vw|Way|Way|Wood|Wd|West|West|Walk|Wk|Walkway|Wkwy|Waters|Wtrs|Waterway|Wry|Wynd|Wyd)[.]??"+
		"[ ,_]+(([A-Z]['a-zA-Z-]+[ ]?){1,10})" +
		"[ ]*([0-9]{4,5})?" +
		"[ ,]+(Australian Capital Territory|A[.]?C[.]?T[.]?|New South Wales|N[.]?S[.]?W[.]?|Northern Territory|N[.]?T[.]?|Queensland|Q[.]?L[.]?D[.]?|South Australia|S[.]?A[.]?|Tasmania|T[.]?A[.]?S[.]?|Victoria|V[.]?I[.]?C[.]?|Western Australia|W[.]?A[.]?)?"+
		"[ ]*([0-9]{4,5})?", // Australian Street, Suburb, State and Postcode
		"[ ]+(([A-Z]['a-zA-Z-]+[ ]?){1,10})" +
		"[ ]*([0-9]{4,5})?" +
		"[ ,]*(Australian Capital Territory|A[.]?C[.]?T[.]?|New South Wales|N[.]?S[.]?W[.]?|Northern Territory|N[.]?T[.]?|Queensland|Q[.]?L[.]?D[.]?|South Australia|S[.]?A[.]?|Tasmania|T[.]?A[.]?S[.]?|Victoria|V[.]?I[.]?C[.]?|Western Australia|W[.]?A[.]?)?"+
		"[ ]*([0-9]{4,5})?" +
		"[ ]*[,][ ]*(([A-Z]['a-zA-Z-]+[ ]?){1,3})[ ](Alley|Al|Amble|Amb|Approach|Appr|Arcade|Arc|Arterial|Art|Avenue|Av|Ave|Bay|Bay|Bend|Bend|Brae|Brae|Break|Brk|Boulevard|Bvd|Boardwalk|Bwk|Bowl|Bwl|Bypass|Byp|Circle|Ccl|Circus|Ccs|Circuit|Cct|Chase|Cha|Close|Cl|Corner|Cnr|Common|Com|Concourse|Con|Crescent|Cr|Cres|Cross|Cros|Course|Crse|Crest|Crst|Cruiseway|Cry|Court|Ct|Cove|Cv|Dale|Dale|Dell|Dell|Dene|Dene|Divide|Div|Domain|Dom|Drive|Dr|East|East|Edge|Edg|Entrance|Ent|Esplanade|Esp|Extension|Extn|Flats|Flts|Ford|Ford|Freeway|Fwy|Gate|Gate|Gardens|Garden|Gdn|Glade|Glades|Gla|Glen|Gln|Gully|Gly|Grange|Gra|Green|Grn|Grove|Gv|Gateway|Gwy|Hill|Hill|Hollow|Hlw|Heath|Hth|Heights|Hts|Hub|Hub|Highway|Hwy|Island|Id|Junction|Jct|Kingsway|Lane|La|Link|Lnk|Loop|Loop|Lower|Lwr|Laneway|Lwy|Mall|Mall|Mew|Mew|Mews|Mws|Nook|Nook|North|Nth|Outlook|Out|Path|Path|Parade|Pd|Pde|Pocket|Pkt|Parkway|Pkw|Place|Pl|Plaza|Plz|Promenade|Prm|Pass|Ps|Passage|Psg|Point|Pt|Pursuit|Pur|Pathway|Pway|Quadrant|Qd|Quay|Qu|Reach|Rch|Road|Rd|Roadway|Rwy|Ridge|Rdg|Reserve|Rest|Rest|Rest|Retreat|Ret|Ride|Ride|Rise|Rise|Round|Rnd|Row|Row|Rising|Rsg|Return|Rtn|Run|Run|Slope|Slo|Square|Sq|Street|St|Strait|South|Sth|Strip|Stp|Steps|Stps|Subway|Sub|Terrace|Tce|Throughway|Thru|Tor|Tor|Track|Trk|Trail|Trl|Turn|Turn|Tollway|Twy|Upper|Upr|Valley|Vly|Vista|Vst|View|Vw|Way|Way|Wood|Wd|West|West|Walk|Wk|Walkway|Wkwy|Waters|Wtrs|Waterway|Wry|Wynd|Wyd)[.]??",
		// Australian Suburb, State and Postcode, followed by Street
		"[ _]+(([A-Z]['a-zA-Z-]+[ ]?){1,3})" +
		"[ ]*([0-9]{4,5})?" +
		"[ ,]+(Australian Capital Territory|A[.]?C[.]?T[.]?|New South Wales|N[.]?S[.]?W[.]?|Northern Territory|N[.]?T[.]?|Queensland|Q[.]?L[.]?D[.]?|South Australia|S[.]?A[.]?|Tasmania|T[.]?A[.]?S[.]?|Victoria|V[.]?I[.]?C[.]?|Western Australia|W[.]?A[.]?)"+
		"[ ]*([0-9]{4,5})") // Australian Suburb, State and Postcode
	val coordinatesFinder = List("(?i)LatLng[(]([-]?[0-9.]+)[ ]?[,][ ]?([-]?[0-9.]+)[)](?-i)", // using google.maps.LatLng()
		"(?i)[(]([-]?[0-9.]+)[ ]?[,][ ]?([-]?[0-9.]+)[)](?-i)")
	val latitudeFinder = List("""(?i)([a-z]*?lat[=]|[a-z]*?latitude[=])["']?([0-9.-])["']?(?-i)""")
	val longitudeFinder = List("""(?i)([a-z]*?lng[=]|[a-z]*?long[=]|[a-z]*?longitude[=])["']?([0-9.-])["']?(?-i)""")
	val auctionDateTimeFinder = List("(?i)(Auction)([ a-z:-]*)?[ _]{1,2}" +
		"(((Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Mon|Tue[s]?|Wed|Thur[s]?|Fri|Sat|Sun)[ ,]+)?" +
		"(((((0?[1-9]|[12]\\d|30)(st|nd|rd|th)?[/-](1[012]|0?[13456789])([/-]((1[6-9]|[2-9]\\d)?\\d{2}))?)|" +
		"((0?[1-9]|[12]\\d|3[01])(st|nd|rd|th)?[/-](1[02]|0?[13578])([/-]((1[6-9]|[2-9]\\d)?\\d{2}))?)|" +
		"((0?[1-9]|1\\d|2[0-8])(st|nd|rd|th)?[/-]0?2([/-]((1[6-9]|[2-9]\\d)?\\d{2}))?)|" +
		"(29(th)?[/-]0?2([/-]((1[6-9]|[2-9]\\d)?(0[48]|[2468][048]|[13579][26])|((16|[2468][048]|[3579][26])00)|00))?))|" +
		"((31(st)?(?! (February|FEB|April|APR|June|JUN|September|SEP[T]?|November|NOV)))|" +
		"((30|29)(th)?(?! (February|FEB)))|" +
		"(29(th)?(?= (February|FEB) (((1[6-9]|[2-9]\\d)(0[48]|[2468][048]|[13579][26])|((16|[2468][048]|[3579][26])00)))?))|" +
		"(0?[1-9])|1\\d|2[0-8])(st|nd|rd|th)?[- ](January|JAN|February|FEB|March|MAR|MAY|April|APR|July|JUL|June|JUN|August|AUG|October|OCT|September|SEP[T]?|November|NOV|December|DEC)([- ]((1[6-9]|[2-9]\\d)\\d{2}))?)" + // Date required
		"[ _@atodby]*(((0?[1-9]|1[012])([:.][0-5]\\d){0,2}([ ]?[AP][.]?M[.]?))|([01]\\d|2[0-3])([:.][0-5]\\d){1,2})?))(?-i)")
	val inspectDateTimeFinder = List("(?i)(Inspect|View)([ a-z:-]*)?[ _]{1,2}" +
		"(((Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Mon|Tue[s]?|Wed|Thur[s]?|Fri|Sat|Sun)[ ,]+)?" +
		"((((((0?[1-9]|[12]\\d|30)(st|nd|rd|th)?[/-](1[012]|0?[13456789])([/-]((1[6-9]|[2-9]\\d)?\\d{2}))?)|" +
		"((0?[1-9]|[12]\\d|3[01])(st|nd|rd|th)?[/-](1[02]|0?[13578])([/-]((1[6-9]|[2-9]\\d)?\\d{2}))?)|" +
		"((0?[1-9]|1\\d|2[0-8])(st|nd|rd|th)?[/-]0?2([/-]((1[6-9]|[2-9]\\d)?\\d{2}))?)|" +
		"(29(th)?[/-]0?2([/-]((1[6-9]|[2-9]\\d)?(0[48]|[2468][048]|[13579][26])|((16|[2468][048]|[3579][26])00)|00))?))|" +
		"((31(st)?(?! (February|FEB|April|APR|June|JUN|September|SEP[T]?|November|NOV)))|" +
		"((30|29)(th)?(?! (February|FEB)))|" +
		"(29(th)?(?= (February|FEB) (((1[6-9]|[2-9]\\d)(0[48]|[2468][048]|[13579][26])|((16|[2468][048]|[3579][26])00)))?))|" +
		"(0?[1-9])|1\\d|2[0-8])(st|nd|rd|th)?[- ](January|JAN|February|FEB|March|MAR|MAY|April|APR|July|JUL|June|JUN|August|AUG|October|OCT|September|SEP[T]?|November|NOV|December|DEC)([- ]((1[6-9]|[2-9]\\d)\\d{2}))?)" + // Date required
		"[ _@atodby]*((((0?[1-9]|1[012])([:.][0-5]\\d){0,2}([ ]?[AP][.]?M[.]?))|([01]\\d|2[0-3])([:.][0-5]\\d){1,2})[ -]*)+))[ _]*)+(?-i)") // Find a time range
	val dateAndTimeFinder = List("(?i)(((Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Mon|Tue[s]?|Wed|Thur[s]?|Fri|Sat|Sun)[ ,]+)?" +
		"(((((0?[1-9]|[12]\\d|30)(st|nd|rd|th)?[/-](1[012]|0?[13456789])([/-]((1[6-9]|[2-9]\\d)?\\d{2}))?)|" +
		"((0?[1-9]|[12]\\d|3[01])(st|nd|rd|th)?[/-](1[02]|0?[13578])([/-]((1[6-9]|[2-9]\\d)?\\d{2}))?)|" +
		"((0?[1-9]|1\\d|2[0-8])(st|nd|rd|th)?[/-]0?2([/-]((1[6-9]|[2-9]\\d)?\\d{2}))?)|" +
		"(29(th)?[/-]0?2([/-]((1[6-9]|[2-9]\\d)?(0[48]|[2468][048]|[13579][26])|((16|[2468][048]|[3579][26])00)|00))?))|" +
		"((31(st)?(?! (February|FEB|April|APR|June|JUN|September|SEP[T]?|November|NOV)))|" +
		"((30|29)(th)?(?! (February|FEB)))|" +
		"(29(th)?(?= (February|FEB) (((1[6-9]|[2-9]\\d)(0[48]|[2468][048]|[13579][26])|((16|[2468][048]|[3579][26])00)))?))|" +
		"(0?[1-9])|1\\d|2[0-8])(st|nd|rd|th)?[- ](January|JAN|February|FEB|March|MAR|MAY|April|APR|July|JUL|June|JUN|August|AUG|October|OCT|September|SEP[T]?|November|NOV|December|DEC)([- ]((1[6-9]|[2-9]\\d)\\d{2}))?)" +  // Date required
		"[ _@atodby]*((((0?[1-9]|1[012])([:.][0-5]\\d){0,2}([ ]?[AP][.]?M[.]?))|([01]\\d|2[0-3])([:.][0-5]\\d){1,2})[ -]*)+))(?-i)") // Find time range

	val trainingOutput = {
		val path = (Configuration.config \ "indexer" \ "training" \ "output").as[String]
		if (!path.startsWith("/"))
			System.getProperty("user.dir") + "/" + path
		else if (path.startsWith("./"))
			System.getProperty("user.dir") + path.drop(1)
		else
			path
	}


	/**
	  * Establish the types of messages that will be handled by the actor
	  *
	  * @return
	  */
	def receive = {
		// Perform basic processing of data to obtain attributes
		case ProcessBasicData(crawlMethod, urlChain, content, category) => {
			// Obtain url from url chain
			val url = urlChain.head

			try {
				Logger.trace(s"Processing basic data: ${url.toString}")
				// Establish propertyTrainingIdentifier for url
				val fullPath = Tools.urlToIdentifier(url)

				val dataIdentifier = digest.digest(fullPath.getBytes(stringType)).map(stringFormatter.format(_)).mkString
				// val dataIdentifier = System.currentTimeMillis.toString
				val dataPoints: HashMap[String, Any] = HashMap.empty

				// Establish base content for analysis such as core text, title and metadata
				val page = Tools.htmlToXml(Tools.stringDecompress(content))
				val basicContent = Processing.extractPlainText(page)
				val titleText = Processing.titleTag(page)
				val metaHash: Map[String, String] = Processing.metaTags(page)
				val metaText = Processing.metaTagsAsText(metaHash)
				val rawContent = titleText + " _ " + metaText + " _ " + basicContent

				// Establish data attributes for specific data set
				val attributeDataPoints = s"$dataSourcesIdentifier.$dataIdentifier."

				// Provide base data being path, content, last update time and meta list
				dataPoints += (attributeDataPoints+urlIdentifier -> fullPath)
				dataPoints += (attributeDataPoints+rawContentIdentifier -> basicContent)
				dataPoints += (attributeDataPoints+metaTextIdentifier -> metaHash)

				// Obtain co-ordinates if available
				val coordinates = findAddressCoordinates(page, rawContent)
				if (coordinates.isDefined)
					dataPoints += (attributeDataPoints+coordinatesIdentifier -> List(coordinates.get._1, coordinates.get._2))

				// Obtain raw Addresses
				val rawAddresses: List[Tuple3[Int, String, List[String]]] = constructRawAddresses(page, rawContent)

				// Compress raw content before passing on for further processing
				val compressedRawContent = Tools.stringCompress(rawContent)

				// Pass on the data for processing the location
				locationProcessRouter ! ProcessLocation(crawlMethod, dataPoints, compressedRawContent, content, rawAddresses, category, dataIdentifier)
			}
			catch {
				case e: Exception => Logger.error(s"Failed to process basic data: ${e.getMessage} for ${url.toString}", error = e)
			}
		}

		// Process raw data points
	    case ProcessRawDataPoints(crawlMethod, urlIdentifier, address, rawContent, content, category, dataIdentifier) => {
			Logger.trace(s"Processing raw data points: ${address}")

			// Set up data points for processing
			val prepDataPoints: HashMap[String, Option[Any]] = HashMap.empty[String, Option[Any]]
			val dataPoints: HashMap[String, Any] = HashMap.empty[String, Any]

			// Establish data attributes for specific data set
			val attributeDataPoints = s"$dataSourcesIdentifier.$dataIdentifier."

			val decompressedRawContent = Tools.stringDecompress(rawContent)

			// Find and select raw single data points
			val page = Tools.htmlToXml(Tools.stringDecompress(content))

			prepDataPoints += (propertyIdIdentifier -> findPropertyID(page, decompressedRawContent))
			prepDataPoints += (statusIdentifier -> findStatus(page, decompressedRawContent))
			prepDataPoints += (auctionDateIdentifier -> findAuctionDate(page, decompressedRawContent))
			prepDataPoints += (inspectionDatesIdentifier -> findInspectionDates(page, decompressedRawContent))
			prepDataPoints += (typeIdentifier -> findPropertyType(page, decompressedRawContent))
    		prepDataPoints += (bedroomsIdentifier -> findBedrooms(page, decompressedRawContent))
    		prepDataPoints += (bathroomsIdentifier -> findBathrooms(page, decompressedRawContent))
			prepDataPoints += (toiletsIdentifier -> findToilets(page, decompressedRawContent))
			prepDataPoints += (energyIdentifier -> findEnergyRating(page, decompressedRawContent))
    		prepDataPoints += (carSpacesIdentifier -> findCarSpaces(page, decompressedRawContent))
    		prepDataPoints += (waterRatesIdentifier -> findWaterRates(page, decompressedRawContent))
    		prepDataPoints += (councilRatesIdentifier -> findCouncilRates(page, decompressedRawContent))
    		prepDataPoints += (strataRatesIdentifier -> findStrataLevies(page, decompressedRawContent))
    		prepDataPoints += (rentIdentifier -> findRent(page, decompressedRawContent))
    		prepDataPoints += (areaIdentifier -> findArea(page, decompressedRawContent))
    	  	prepDataPoints += (priceIdentifier -> findPrice(page, decompressedRawContent))
			prepDataPoints += (bondIdentifier -> findBond(page, decompressedRawContent))

			// Set raw available data points ready for storage
    		prepDataPoints foreach {
				case (key: String, value: Option[Any]) => {
					if (value.isDefined)
						dataPoints += (attributeDataPoints+key -> value.get)
				}
    		}

			// Review scoring for property given status information
			if (dataPoints.contains(attributeDataPoints+statusIdentifier)) {
				val score = dataPoints.get(attributeDataPoints+statusIdentifier) match {
					case Some("sold") => ScoringEngine.resetScore(urlIdentifier, crawlMethod)
					case Some("auctioned") => ScoringEngine.resetScore(urlIdentifier, crawlMethod)
					case Some("leased") => ScoringEngine.resetScore(urlIdentifier, crawlMethod)
					case Some("rented") => ScoringEngine.resetScore(urlIdentifier, crawlMethod)
					case _ => ScoringEngine.getScore(urlIdentifier)
				}

				Logger.debug(s"Refined score to ${score} following review of property information for url - ${urlIdentifier}", Logger.VERBOSE_INTENSITY)
			}

    		// Store the data with an update to property information
        	propertyFactoryRouter ! UpdateProperty(crawlMethod, address, dataPoints, dataIdentifier)
    	}

		case ProcessToClassify(crawlMethod, urlChain, content, category) => {
			// Establish base content for analysis such as core text, title and metadata
			val page = Tools.htmlToXml(Tools.stringDecompress(content))
			val basicContent = Processing.extractPlainText(page)

			val dataToWrite = category match {
				case PropertyCategory() => "Property " + basicContent + "\n"
				case MiscellaneousCategory() => "Miscellaneous " + basicContent + "\n"
			}

			try {
				Files.write(Paths.get(trainingOutput), dataToWrite.getBytes, StandardOpenOption.APPEND)
			}
			catch {
				case e: Exception => Logger.error(s"Failed to write data - possibly file missing?: ${e.getMessage}", error = e)
			}
		}
  	}


	/**
	  * Perform a search for the property type (e.g. apartment) using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
  	def findPropertyType(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
  	    val result = Processing.plainTextSingleDataPointFirstFinder(plainText, typeFinder)

		// Return the result if a match is found
		if (result.isDefined)
			Some(result.get)
		else
			None
    }

	/**
	  * Perform a search for the number of bedrooms using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
  	def findBedrooms(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
		val result = Processing.plainTextSingleDataPointFirstFinder(plainText, bedFinder)

		// Return the result if a match is found
		if (result.isDefined)
			return Some(result.get)

		// Perform a search starting with attribute matching return element values
  	    val results = Processing.elementAttributeContainsDataPointAllFinder(content, List("class","id"), "bed")

		// If a match is found for a number value for any of the results, return the value
		results foreach { result =>
			if ((number findFirstIn result).isDefined)
				return Some((number findFirstIn result).get)
		}

		// Next perform a search for matching text and see if can find number around this
		val textNodes = Processing.elementTextContainsDataPointAllFinderAsElement(content, "bed")

		// If a match is found for a number value for any of the results, return the value
		textNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((number findFirstIn plainText).isDefined)
				return Some((number findFirstIn plainText).get)
		}

		// Next perform a search for matching attribute value containing and see if can find number around this
		val childAttrNodes = Processing.childAttributesContainsDataPointAllFinderAsElement(content, "bed")

		// If a match is found for a number value for any of the results, return the value
		childAttrNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((number findFirstIn plainText).isDefined)
				return Some((number findFirstIn plainText).get)
		}

		None
    }

	/**
	  * Perform a search for the number of bathrooms using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
  	def findBathrooms(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
		val result = Processing.plainTextSingleDataPointFirstFinder(plainText, bathFinder)

		// Return the result if a match is found
		if (result.isDefined)
			return Some(result.get)

		// Perform a search starting with attribute matching return element values
		val results = Processing.elementAttributeContainsDataPointAllFinder(content, List("class","id"), "bath")

		// If a match is found for a number value for any of the results, return the value
		results foreach { result =>
			if ((number findFirstIn result).isDefined)
				return Some((number findFirstIn result).get)
		}

		// Next perform a search for matching text and see if can find number around this
		val textNodes = Processing.elementTextContainsDataPointAllFinderAsElement(content, "bath")

		// If a match is found for a number value for any of the results, return the value
		textNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((number findFirstIn plainText).isDefined)
				return Some((number findFirstIn plainText).get)
		}

		// Next perform a search for matching attribute value containing and see if can find number around this
		val childAttrNodes = Processing.childAttributesContainsDataPointAllFinderAsElement(content, "bath")

		// If a match is found for a number value for any of the results, return the value
		childAttrNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((number findFirstIn plainText).isDefined)
				return Some((number findFirstIn plainText).get)
		}

		None
  	}

	/**
	  * Perform a search for the number of car spaces using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
  	def findCarSpaces(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
		val result = Processing.plainTextSingleDataPointFirstFinder(plainText, carFinder)

		// Return the result if a match is found
		if (result.isDefined)
			return Some(result.get)

		// Perform a search starting with attribute matching return element values
		val results = Processing.elementAttributeContainsDataPointAllFinder(content, List("class","id"), "car")

		// If a match is found for a number value for any of the results, return the value
		results foreach { result =>
			if ((number findFirstIn result).isDefined)
				return Some((number findFirstIn result).get)
		}

		// Next perform a search for matching text and see if can find number around this
		val textNodes = Processing.elementTextContainsDataPointAllFinderAsElement(content, "car")

		// If a match is found for a number value for any of the results, return the value
		textNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((number findFirstIn plainText).isDefined)
				return Some((number findFirstIn plainText).get)
		}

		// Next perform a search for matching attribute value containing and see if can find number around this
		val childAttrNodes = Processing.childAttributesContainsDataPointAllFinderAsElement(content, "car")

		// If a match is found for a number value for any of the results, return the value
		childAttrNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((number findFirstIn plainText).isDefined)
				return Some((number findFirstIn plainText).get)
		}

		None
  	}

	/**
	  * Perform a search for the number of toilets using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
	def findToilets(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
		val result = Processing.plainTextSingleDataPointFirstFinder(plainText, toiletFinder)

		// Return the result if a match is found
		if (result.isDefined)
			return Some(result.get)

		// Perform a search starting with attribute matching return element values
		val results = Processing.elementAttributeContainsDataPointAllFinder(content, List("class","id"), "toilet")

		// If a match is found for a number value for any of the results, return the value
		results foreach { result =>
			if ((number findFirstIn result).isDefined)
				return Some((number findFirstIn result).get)
		}

		// Next perform a search for matching text and see if can find number around this
		val textNodes = Processing.elementTextContainsDataPointAllFinderAsElement(content, "toilet")

		// If a match is found for a number value for any of the results, return the value
		textNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((number findFirstIn plainText).isDefined)
				return Some((number findFirstIn plainText).get)
		}

		// Next perform a search for matching attribute value containing and see if can find number around this
		val childAttrNodes = Processing.childAttributesContainsDataPointAllFinderAsElement(content, "toilet")

		// If a match is found for a number value for any of the results, return the value
		childAttrNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((number findFirstIn plainText).isDefined)
				return Some((number findFirstIn plainText).get)
		}

		None
	}

	/**
	  * Perform a search for the energy rating using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
	def findEnergyRating(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
		val result = Processing.plainTextSingleDataPointFirstFinder(plainText, energyFinder)

		// Return the result if a match is found
		if (result.isDefined)
			return Some(result.get)

		None
	}

	/**
	  * Perform a search for the water rates using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
  	def findWaterRates(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
		val result = Processing.plainTextSingleDataPointFirstFinder(plainText, waterRateFinder)

		// Return the result if a match is found
		if (result.isDefined)
			return Some(result.get)

		// Perform a search starting with attribute matching return element values
		val results = Processing.elementAttributeContainsDataPointAllFinder(content, List("class","id"), "water")

		// If a match is found for a number value for any of the results, return the value
		results foreach { result =>
			if ((price findFirstIn result).isDefined)
				return Some((price findFirstIn result).get)
		}

		// Next perform a search for matching text and see if can find number around this
		val textNodes = Processing.elementTextContainsDataPointAllFinderAsElement(content, "water")

		// If a match is found for a number value for any of the results, return the value
		textNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((price findFirstIn plainText).isDefined)
				return Some((price findFirstIn plainText).get)
		}

		None
  	}

	/**
	  * Perform a search for the council rates using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
  	def findCouncilRates(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
		val result = Processing.plainTextSingleDataPointFirstFinder(plainText, councilRateFinder)

		// Return the result if a match is found
		if (result.isDefined)
			return Some(result.get)

		// Perform a search starting with attribute matching return element values
		val results = Processing.elementAttributeContainsDataPointAllFinder(content, List("class","id"), "council")

		// If a match is found for a number value for any of the results, return the value
		results foreach { result =>
			if ((price findFirstIn result).isDefined)
				return Some((price findFirstIn result).get)
		}

		// Next perform a search for matching text and see if can find number around this
		val textNodes = Processing.elementTextContainsDataPointAllFinderAsElement(content, "council")

		// If a match is found for a number value for any of the results, return the value
		textNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((price findFirstIn plainText).isDefined)
				return Some((price findFirstIn plainText).get)
		}

		None
  	}

	/**
	  * Perform a search for the strata levies using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
  	def findStrataLevies(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a group match
		val results_2 = Processing.plainTextSingleDataPointFirstGroupFinder(plainText, strataLevyFinder, 1)
		var total = ""

		// Work through each result and merge comma separated
		results_2 foreach { result =>
			total += result + ", "
		}

		// Return the result if a match is found
		if (total.length > 0)
			return Some(total dropRight 2)

		// Perform a search starting with attribute matching return element values
		val results = Processing.elementAttributeContainsDataPointAllFinder(content, List("class","id"), "strata")

		// If a match is found for a number value for any of the results, return the value
		results foreach { result =>
			if ((price findFirstIn result).isDefined)
				return Some((price findFirstIn result).get)
		}

		// Next perform a search for matching text and see if can find number around this
		val textNodes = Processing.elementTextContainsDataPointAllFinderAsElement(content, "strata")

		// If a match is found for a number value for any of the results, return the value
		textNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((price findFirstIn plainText).isDefined)
				return Some((price findFirstIn plainText).get)
		}

		None
  	}

	/**
	  * Perform a search for the rent amount using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
  	def findRent(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
		val result = Processing.plainTextSingleDataPointFirstFinder(plainText, rentFinder)

		// Return the result if a match is found
		if (result.isDefined)
			return Some(result.get)

		// Next perform a search for matching text and see if can find number around this
		val textNodes = Processing.elementTextContainsDataPointAllFinderAsElement(content, "rent")

		// If a match is found for a number value for any of the results, return the value
		textNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((price findFirstIn plainText).isDefined)
				return Some((price findFirstIn plainText).get)
		}

		// Perform a search starting with attribute matching return element values
		val results = Processing.elementAttributeContainsDataPointAllFinder(content, List("class","id"), "rent")

		// If a match is found for a number value for any of the results, return the value
		results foreach { result =>
			if ((price findFirstIn result).isDefined)
				return Some((price findFirstIn result).get)
		}

		None
  	}

	/**
	  * Perform a search for the bond amount using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
	def findBond(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
		val result = Processing.plainTextSingleDataPointFirstFinder(plainText, bondFinder)

		// Return the result if a match is found
		if (result.isDefined)
			return Some(result.get)

		// Perform a search starting with attribute matching return element values
		val results = Processing.elementAttributeContainsDataPointAllFinder(content, List("class","id"), "bond")

		// If a match is found for a number value for any of the results, return the value
		results foreach { result =>
			if ((price findFirstIn result).isDefined)
				return Some((price findFirstIn result).get)
		}

		// Next perform a search for matching text and see if can find number around this
		val textNodes = Processing.elementTextContainsDataPointAllFinderAsElement(content, "bond")

		// If a match is found for a number value for any of the results, return the value
		textNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((price findFirstIn plainText).isDefined)
				return Some((price findFirstIn plainText).get)
		}

		None
	}

	/**
	  * Perform a search for the price using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
  	def findPrice(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
		val result = Processing.plainTextSingleDataPointFirstFinder(plainText, priceFinder, 2)

		// Return the result if a match is found
		if (result.isDefined)
			return Some(result.get)

		// Perform a search starting with attribute matching return element values
		val results = Processing.elementAttributeContainsDataPointAllFinder(content, List("class","id"), "price")

		// If a match is found for a number value for any of the results, return the value
		results foreach { result =>
			if ((priceRange findFirstIn result).isDefined)
				return Some((priceRange findFirstIn result).get)
		}

		// Next perform a search for matching text and see if can find number around this
		val textNodes = Processing.elementTextContainsDataPointAllFinderAsElement(content, "price")

		// If a match is found for a number value for any of the results, return the value
		textNodes foreach { node =>
			val plainText = Processing.extractPlainText(node)
			if ((priceRange findFirstIn plainText).isDefined)
				return Some((priceRange findFirstIn plainText).get)
		}

		None
  	}

	/**
	  * Perform a search for the property status (e.g. under contract) using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
  	def findStatus(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
  	    val result = Processing.plainTextSingleDataPointFirstFinder(plainText, statusFinder, 1)

		// Return the result if a match is found
		if (result.isDefined)
			return Some(result.get)

		None
  	}

	/**
	  * Perform a search for the auction date and time using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
	def findAuctionDate(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match, looking for date and time
		val result = Processing.plainTextMultiDataPointFirstFinder(plainText, auctionDateTimeFinder)

		if (result.isDefined) {
			val data: List[String] = result.get

			val date = data(6)
			val time = data(51)

			// Establish whether date and time exist and set if so
			if (date != null && time == null)
				Some(date)
			else if (date == null && time != null)
				Some(time)
			else if (date != null && time != null)
				Some(date + " @ " + time)
			else
				None
		}
		else
			None
	}

	/**
	  * Perform a search for the inspection dates and times using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
	def findInspectionDates(content: Elem, plainText: String): Option[List[String]] = {
		// Perform a search over the plain text for a match to see if anything exists
		val data: Option[String] = Processing.plainTextFullDataFirstFinder(plainText, inspectDateTimeFinder)
		val outcome: ListBuffer[String] = ListBuffer.empty

		// If a date exists, look for all options
		if (data.isDefined) {
			// Perform a search over the plain text for all matches
			val results = Processing.plainTextFullDataAllFinder(data.get, dateAndTimeFinder)

			// For all results, add them to the list
			if (results.isDefined) {
				results.get foreach { result =>
					outcome += result
				}
			}
		}

		// Return the list
		if (outcome.nonEmpty)
			Some(outcome.toList)
		else
			None
	}

	/**
	  * Perform a search for the site property propertyTrainingIdentifier using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
	def findPropertyID(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
		val result = Processing.plainTextSingleDataPointFirstFinder(plainText, idFinder, 1)

		// Return the result if a match is found
		if (result.isDefined)
			Some(result.get)
		else
			None
	}

	/**
	  * Perform a search for the physical area using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
	def findArea(content: Elem, plainText: String): Option[String] = {
		// Perform a search over the plain text for a match
  	    val result = Processing.plainTextSingleDataPointFirstFinder(plainText, areaFinder)

		// Return the result if a match is found
		if (result.isDefined)
			Some(result.get)
		else
			None
  	}

	/**
	  * Perform a search for an address using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
  	def findAddress(content: Elem, plainText: String): List[List[String]] = {
		// Perform a search over the plain text for any matches
  	    Processing.plainTextMultiDataPointAllFinderWithSelector(plainText, addressFinder)
  	}

	/**
	  * Perform a search for co-ordinates using the search pattern finder and return the result if found
	  *
	  * @param content
	  * @param plainText
	  * @return
	  */
  	def findAddressCoordinates(content: Elem, plainText: String): Option[Tuple2[String, String]] = {
		// Perform a search over the raw source for any matches
  	    val coordinates = Processing.rawSourceContainsMultiDataPointFirstFinder(content, coordinatesFinder)

		// If something has been found, return it
  	    if (coordinates.isDefined)
  	        return Some(Tuple2(coordinates.get(0), coordinates.get(1)))
		// Otherwise attempt to find the latitude and longitude individually
  	    else {
  	        val latitude = Processing.rawSourceContainsDataPointFirstFinder(content, latitudeFinder, 1)
  	        val longitude = Processing.rawSourceContainsDataPointFirstFinder(content, longitudeFinder, 1)

			// Return match result
  	        if (latitude.isDefined && longitude.isDefined && latitude.get != "0" && longitude.get != "0")
  	            return Some(Tuple2(latitude.get, longitude.get))
  	    }
  	    
  	    None
  	}

	/**
	  * Construct raw addresses by taking the individual components and putting the pieces together
	  * to create a single line address
	  * The number at the beginning is based on the selector (starting at 1) from the regex list of expressions
	  * Note that the Processing method called places this selector value at position 0, so all regex positions for subgroups start at 1
	  * in this function and then are moved back to their zero position
	  *
	  * @param content
	  * @param propertyContent
	  * @return
	  */
  	def constructRawAddresses(content: Elem, propertyContent: String): List[Tuple3[Int, String, List[String]]] = {
  	    // Locate all matching addresses as found
		val addressesComponents: List[List[String]] = findAddress(content, propertyContent)
		var rawAddresses: ListBuffer[Tuple3[Int, String, List[String]]] = ListBuffer()

		// For each address pull out all the components
		addressesComponents foreach { addressComponents =>
			var selector: Int = addressComponents(0).toInt

			// Pull each component and tear pieces out that are not relevant
			val components = {
				val comps = addressComponents.drop(1)
				val result = ListBuffer.empty[String]
				comps foreach { comp =>
					if (comp == null)
						result += ""
					else
						result += comp
				}

				result.toList
			}

			// Construct address string
			val addressString = Processing.fullAddressConstructor(selector, components).replaceAll("\\s{2,}", " ").trim

			// Add the final address to the raw address list
    		rawAddresses += Tuple3(selector, addressString, components)
		}

		rawAddresses.toList
  	}
}