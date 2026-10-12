package com.mavislibrary.app.ui.scan

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ScanManifestTest {
    @Test fun cameraPermissionAndOptionalHardware() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val ns = "http://schemas.android.com/apk/res/android"
        val permissions = doc.getElementsByTagName("uses-permission")
        assertTrue((0 until permissions.length).any { permissions.item(it).attributes.getNamedItemNS(ns, "name")?.nodeValue == "android.permission.CAMERA" })
        val features = doc.getElementsByTagName("uses-feature")
        assertTrue((0 until features.length).any { i ->
            val attrs = features.item(i).attributes
            attrs.getNamedItemNS(ns, "name")?.nodeValue == "android.hardware.camera" && attrs.getNamedItemNS(ns, "required")?.nodeValue == "false"
        })
    }
}
