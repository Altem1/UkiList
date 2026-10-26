package knf.kuma.anilist

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * SSLSkipper (al abrir el reproductor) pone jsse.enableSNIExtension=false de forma global.
 * AniList está detrás de Cloudflare y sin SNI el handshake falla, así que lo forzamos solo aquí.
 */
class SniSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

    private fun Socket.withSni(host: String?): Socket = apply {
        if (this is SSLSocket && !host.isNullOrBlank())
            sslParameters = sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        delegate.createSocket(s, host, port, autoClose).withSni(host)
    override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port).withSni(host)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).withSni(host)
    override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port).withSni(host.hostName)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).withSni(address.hostName)
}
