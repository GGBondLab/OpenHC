package io.github.gcbond.openhc.platform.usb

import android.hardware.usb.UsbDevice

/** 安卓 `UsbDevice` → [UsbDeviceDescription]。 */
object UsbDescriber {

    fun describe(device: UsbDevice): UsbDeviceDescription {
        val interfaces = (0 until device.interfaceCount).map { i ->
            val iface = device.getInterface(i)
            UsbInterfaceDescription(
                index = i,
                id = iface.id,
                alternateSetting = iface.alternateSetting,
                interfaceClass = iface.interfaceClass,
                interfaceSubclass = iface.interfaceSubclass,
                interfaceProtocol = iface.interfaceProtocol,
                endpoints = (0 until iface.endpointCount).map { e ->
                    val endpoint = iface.getEndpoint(e)
                    UsbEndpointDescription(
                        index = e,
                        address = endpoint.address,
                        type = endpoint.type,
                        maxPacketSize = endpoint.maxPacketSize,
                    )
                },
            )
        }
        return UsbDeviceDescription(device.vendorId, device.productId, device.deviceClass, interfaces)
    }

    /** 这台设备从有线投屏的角度看处于哪种状态，给界面显示用。不分配接口/端点描述列表。 */
    fun phoneMode(device: UsbDevice): PhoneUsbMode {
        if (!HuaweiPhone.isHuaweiVendor(device.vendorId)) return PhoneUsbMode.NOT_A_PHONE
        var hasNcmControl = false
        var hasCdcData = false
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val cls = iface.interfaceClass
            if (cls == HuaweiPhone.CLASS_COMM && iface.interfaceSubclass == HuaweiPhone.SUBCLASS_NCM) {
                hasNcmControl = true
            } else if (cls == HuaweiPhone.CLASS_CDC_DATA) {
                hasCdcData = true
            }
            if (hasNcmControl && hasCdcData) return PhoneUsbMode.NCM
        }
        val productId = device.productId
        if (productId in HuaweiPhone.NORMAL_PRODUCT_IDS) return PhoneUsbMode.NORMAL
        if (productId == HuaweiPhone.PRODUCT_USB_DEBUGGING) return PhoneUsbMode.USB_DEBUGGING
        return PhoneUsbMode.UNSUPPORTED
    }

    /** 日志用的简短描述：厂商号、产品号、接口类别。不含序列号等设备标识。 */
    fun summary(device: UsbDevice): String {
        val d = describe(device)
        val interfaces = d.interfaces.joinToString(" ") { "${it.interfaceClass}/${it.interfaceSubclass}" }
        return "%04x:%04x 类 %d 接口 [%s]".format(java.util.Locale.ROOT, d.vendorId, d.productId, d.deviceClass, interfaces)
    }
}
