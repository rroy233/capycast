package com.roy233.tvcast.platform.dlna

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** 表示解析后的 UPnP SOAP 请求。 */
data class SoapRequest(
    /** SOAP 服务标识。 */
    val service: String,
    /** SOAP 操作名称。 */
    val action: String,
    /** 操作参数映射。 */
    val args: Map<String, String>,
)

/** 解析渲染器接收的 UPnP SOAP 请求结构。 */
object SoapRequestParser {

    /**
     * 解析 SOAP 请求体、动作头和服务标识。
     *
     * @param body SOAP XML 请求体。
     * @param soapAction HTTP SOAPAction 请求头。
     * @param serviceFor 根据动作名称推断服务的回调。
     * @return 解析后的 SOAP 请求。
     */
    fun parse(
        body: ByteArray,
        soapAction: String?,
        serviceFor: (String) -> String,
    ): SoapRequest {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(body))
        val bodyNode = doc.getElementsByTagNameNS(SOAP_ENV, "Body").item(0) as? Element
        var actionEl: Element? = null
        if (bodyNode != null) {
            for (i in 0 until bodyNode.childNodes.length) {
                val node = bodyNode.childNodes.item(i)
                if (node is Element) {
                    actionEl = node
                    break
                }
            }
        }
        val action = actionEl?.localName
            ?: soapAction?.substringAfter('#')?.trim('"')
            ?: ""
        val service = actionEl?.namespaceURI
            ?.takeIf { it.isNotBlank() }
            ?: soapAction?.substringBefore('#')?.trim('"')?.takeIf { it.isNotBlank() }
            ?: serviceFor(action)
        val args = mutableMapOf<String, String>()
        if (actionEl != null) {
            for (i in 0 until actionEl.childNodes.length) {
                val node = actionEl.childNodes.item(i)
                if (node is Element) args[node.localName] = node.textContent ?: ""
            }
        }
        return SoapRequest(service, action, args)
    }

    /** SOAP 1.1 命名空间。 */
    private const val SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/"
}
