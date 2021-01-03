package com.adcoelum.network

import java.net.{Inet4Address, Inet6Address, NetworkInterface}

import com.adcoelum.common.{Configuration, Logger}

import scala.collection.JavaConversions._


object Host {
    val idealInterfaces = List("en", "eth", "wlan")

    val allowLocal = (Configuration.config \ "network" \ "allowLocal").as[Boolean]
    val ipNotAllowed = {
        if (allowLocal)
            List("127.")
        else
            List("127.", "192.168.", "10.", "172.16.")
    }
    val hostnameNotAllowed = List("localhost")

    def getInterfaceAddress(iface: String, byHostname: Boolean = false, acceptLocal: Boolean = allowLocal): String = {
        var ifResult = ""
        var result = ""

        NetworkInterface.getNetworkInterfaces foreach { interface =>
            val interfaceName = interface.getName

            if (result == "" && interface.getName == iface) {
                interface.getInetAddresses foreach { address =>
                    if (byHostname) {
                        val hostname = address.getCanonicalHostName
                        var pass = true

                        hostnameNotAllowed foreach { check =>
                            if (hostname.contains(check))
                                pass = false
                        }

                        if (pass) {
                            result = hostname
                            ifResult = interfaceName
                        }
                    }
                    else {
                        val ipAddress = address.getHostAddress
                        if (ipAddress != null) {
                            var pass = true

                            ipNotAllowed foreach { check =>
                                if (ipAddress.startsWith(check))
                                    pass = false
                            }

                            if (pass) {
                                result = ipAddress
                                ifResult = interfaceName
                            }
                        }
                    }
                }
            }
        }

        if (result != "") {
            Logger.trace(s"Host details located for interface ${ifResult} with address: ${result}")
            result
        }
        else
            throw new Exception("Failed to obtain a valid network address")
    }

    def getBestAddress(defaultInterface: String, byHostname: Boolean = false, acceptLocal: Boolean = allowLocal): String = {
        var ifResult = ""
        var result = ""

        NetworkInterface.getNetworkInterfaces foreach { interface =>
            val interfaceName = interface.getName

            idealInterfaces foreach { ideal =>
                if (result == "" && interfaceName.contains(ideal)) {
                    interface.getInetAddresses foreach { address =>
                        if (byHostname) {
                            val hostname = address.getCanonicalHostName
                            var pass = true

                            hostnameNotAllowed foreach { check =>
                                if (hostname.contains(check))
                                    pass = false
                            }

                            if (pass) {
                                result = hostname
                                ifResult = interfaceName
                            }
                        }
                        else {
                            if ((address.isInstanceOf[Inet6Address] &&
                                address.asInstanceOf[Inet6Address].isIPv4CompatibleAddress) ||
                                address.isInstanceOf[Inet4Address]) {
                                val ipAddress = address.getHostAddress

                                if (ipAddress != null) {
                                    var pass = true

                                    ipNotAllowed foreach { check =>
                                        if (ipAddress.startsWith(check))
                                            pass = false
                                    }

                                    if (pass) {
                                        result = ipAddress
                                        ifResult = interfaceName
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (result != "") {
            Logger.trace(s"Host details located for interface ${ifResult} with address: ${result}")
            result
        }
        else
            getInterfaceAddress(defaultInterface)
    }
}
