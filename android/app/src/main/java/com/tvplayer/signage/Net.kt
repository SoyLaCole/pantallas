package com.tvplayer.signage

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Conexiones HTTP/HTTPS. Para HTTPS agrega el certificado raiz de Let's Encrypt (ISRG Root X1)
 * por si el TV es tan viejo que no lo trae de fabrica.
 */
object Net {
    @Volatile private var factory: SSLSocketFactory? = null

    fun open(ctx: Context, url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) c.sslSocketFactory = sslFactory(ctx)
        return c
    }

    private fun sslFactory(ctx: Context): SSLSocketFactory {
        factory?.let { return it }
        synchronized(this) {
            factory?.let { return it }
            val cf = CertificateFactory.getInstance("X.509")
            val cert = ctx.resources.openRawResource(R.raw.isrg_root_x1).use { cf.generateCertificate(it) }
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            ks.setCertificateEntry("isrg", cert)

            val extraTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            extraTmf.init(ks)
            val sysTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            sysTmf.init(null as KeyStore?)
            val sys = sysTmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val extra = extraTmf.trustManagers.filterIsInstance<X509TrustManager>().first()

            val combined = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    sys.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        sys.checkServerTrusted(chain, authType)
                    } catch (e: CertificateException) {
                        extra.checkServerTrusted(chain, authType)
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    sys.acceptedIssuers + extra.acceptedIssuers
            }
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, arrayOf<TrustManager>(combined), null)
            val f = sc.socketFactory
            factory = f
            return f
        }
    }
}
