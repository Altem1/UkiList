package knf.kuma.commons

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object SSLSkipper {
    fun skip() {
        val trustAllCertificates = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate>? {
                return null // Not relevant.
            }

            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {
                noCrash {
                    certs.forEach {
                        it.checkValidity()
                    }
                }
            }

            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {
                noCrash {
                    certs.forEach {
                        it.checkValidity()
                    }
                }
            }
        })

        val trustAllHostnames = HostnameVerifier { hostName, _ -> /*isHostValid(hostName)*/ true }

        try {
            // Servidores como MP4Upload cuelgan el handshake si llega SNI: el reproductor lo necesita apagado.
            // Lo que va a Cloudflare (animeav1, AniList) lo fuerza por su cuenta con SniSocketFactory.
            System.setProperty("jsse.enableSNIExtension", "false")
            val sc = SSLContext.getInstance("SSL")
            sc.init(null, trustAllCertificates, SecureRandom())
            // Sin SNI global, pero animeav1 (Cloudflare) lo exige: sin él el scraping caía en reintentos lentos.
            HttpsURLConnection.setDefaultSSLSocketFactory(knf.kuma.anilist.SniSocketFactory(sc.socketFactory) { it == "animeav1.com" || it.endsWith(".animeav1.com") })
            HttpsURLConnection.setDefaultHostnameVerifier(trustAllHostnames)
        } catch (e: GeneralSecurityException) {
            throw ExceptionInInitializerError(e)
        }

        try {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<X509TrustManager>(object : X509TrustManager {
                @Throws(CertificateException::class)
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    noCrash {
                        chain.forEach {
                            it.checkValidity()
                        }
                    }
                }

                @Throws(CertificateException::class)
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    noCrash {
                        chain.forEach {
                            it.checkValidity()
                        }
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate?> {
                    return arrayOfNulls(0)
                }
            }), SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
        } catch (e: Exception) {

        }

    }
}
