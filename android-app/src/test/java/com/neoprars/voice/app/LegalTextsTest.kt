package com.neoprars.voice.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Los textos legales de "Acerca de" (res/raw) son copias: que no se desactualicen respecto del repositorio. */
class LegalTextsTest {

    // Los tests de la app corren con el módulo android-app como carpeta de trabajo.
    private fun repo(path: String) = File("..", path).readText().replace("\r", "")
    private fun raw(name: String) = File("src/main/res/raw/$name").readText().replace("\r", "")

    @Test fun theAppShowsTheSameLicenseAndNoticeAsTheRepository() {
        assertEquals(repo("LICENSE"), raw("license_app.txt"), "copiá LICENSE a res/raw/license_app.txt")
        assertEquals(repo("NOTICE"), raw("notice_app.txt"), "copiá NOTICE a res/raw/notice_app.txt")
    }

    @Test fun thirdPartyLicensesIncludeTheFullTexts() {
        val text = raw("third_party_licenses.txt")
        assertTrue(text.contains("Apache License\n                           Version 2.0, January 2004"))
        assertTrue(text.contains("END OF TERMS AND CONDITIONS"))
        assertTrue(text.contains("Copyright (c) 2004-2022 QOS.ch Sarl"))
        assertTrue(text.contains("Permission is hereby granted, free  of charge"))
    }
}
