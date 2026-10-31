package com.example.hamkit.data.satellite

import com.example.hamkit.data.satellite.predict.TleElements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TleParserActiveTest {

    @Test
    fun parseActiveCsv_producesValidTleLines() {
        val csv = """OBJECT_NAME,OBJECT_ID,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION,RA_OF_ASC_NODE,ARG_OF_PERICENTER,MEAN_ANOMALY,EPHEMERIS_TYPE,CLASSIFICATION_TYPE,NORAD_CAT_ID,ELEMENT_SET_NO,REV_AT_EPOCH,BSTAR,MEAN_MOTION_DOT,MEAN_MOTION_DDOT
CALSPHERE 1,1964-063C,2026-08-16T06:34:48.927360,13.76678439,.00277166,90.2180,73.1688,108.3343,302.7275,0,U,900,999,7969,.37201857E-3,.376E-5,0"""

        val triples = TleParser.parseActiveCsv(csv)
        println("解析结果数量: ${triples.size}")
        assertTrue("应该解析出至少 1 条 TLE，实际 ${triples.size}", triples.size > 0)

        val (name, line1, line2) = triples[0]
        println("name: [$name] len=${name.length}")
        println("line1: [$line1] len=${line1.length}")
        println("line2: [$line2] len=${line2.length}")

        assertTrue("line1 长度 ${line1.length} < 69", line1.length >= 69)
        assertTrue("line2 长度 ${line2.length} < 69", line2.length >= 69)

        val tle = TleElements.fromThreeLines(arrayOf(name, line1, line2))
        println("TleElements 解析成功: catnum=${tle.catalogNumber} meanmo=${tle.meanMotion}")
        assertEquals(900, tle.catalogNumber)
    }

    @Test
    fun formatExponentialField_zeroValue_hasPlusSign() {
        val row = mapOf(
            "OBJECT_NAME" to "TEST", "OBJECT_ID" to "2020-001A",
            "EPOCH" to "2026-08-16T06:00:00",
            "MEAN_MOTION" to "15.5", "ECCENTRICITY" to "0.001",
            "INCLINATION" to "51.0", "RA_OF_ASC_NODE" to "100.0",
            "ARG_OF_PERICENTER" to "50.0", "MEAN_ANOMALY" to "200.0",
            "EPHEMERIS_TYPE" to "0", "CLASSIFICATION_TYPE" to "U",
            "NORAD_CAT_ID" to "25544", "ELEMENT_SET_NO" to "999",
            "REV_AT_EPOCH" to "9995",
            "BSTAR" to "0.0001", "MEAN_MOTION_DOT" to "0.0",
            "MEAN_MOTION_DDOT" to "0.0"
        )
        val triple = TleParser.buildTle(row)!!
        val line1 = triple.second
        println("line1: [$line1] len=${line1.length}")

        val nddot = line1.substring(44, 52)
        println("nddot field: [$nddot]")
        assertTrue(
            "nddot 指数符号位应该是 + 或 -，实际是 [${nddot[6]}]",
            nddot[6] == '+' || nddot[6] == '-'
        )
    }
}