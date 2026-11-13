package com.seldon.fleet.domain.usecase

import com.seldon.fleet.domain.model.Device
import com.seldon.fleet.domain.model.DeviceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes unitários na JVM para validar e comparar a Trilha A (FleetPercentileClassifier)
 * contra a Regra de Referência (ClassifyDevice) - Seção 10 da Especificação Técnica.
 */
class FleetPercentileClassifierTest {

    private val referenceClassifier = ClassifyDevice()

    private fun sampleDevice(
        id: String,
        tier: String = "mid",
        capacityMah: Int? = 5000,
        screenMinutes: Int? = 60,
        energyMah: Double? = 250.0, // 250 / 5000 = 5.0 %/h
        jankPct: Double = 3.0,
        crashes: Int = 0
    ): Device = Device(
        id = id,
        model = "Model-$id",
        soc = "SoC-$tier",
        tier = tier,
        ramMb = 6144,
        androidApi = 34,
        batteryLevelPct = 50,
        batteryCapacityMah = capacityMah,
        screenOnMinutes = screenMinutes,
        energyConsumedMah = energyMah,
        rawEnergyConsumed = energyMah,
        rawEnergyUnit = "mAh",
        nominalVoltage = 3.85,
        jankFramesPct = jankPct,
        crashCount7d = crashes,
        lastSeen = "2026-10-20T03:00:00Z"
    )

    @Test
    fun `comparacao Trilha A vs Referencia - reduz falsos positivos moderados preservando criticos e indeterminados`() {
        // Simula a distribuição real observada no frota.json (28 válidos + 2 indeterminados)
        val fleet = listOf(
            sampleDevice("DEV-0311", tier = "low", capacityMah = 0, energyMah = 612.0),       // Indeterminado
            sampleDevice("DEV-0745", tier = "low", screenMinutes = 0, energyMah = 0.0),      // Indeterminado
            sampleDevice("DEV-1277", tier = "high", energyMah = 150.0),                      // 3.0 %/h
            sampleDevice("DEV-1258", tier = "low", energyMah = 175.0),                       // 3.5 %/h
            sampleDevice("DEV-1214", tier = "high", energyMah = 180.0),                      // 3.6 %/h
            sampleDevice("DEV-1366", tier = "high", energyMah = 206.0),                      // 4.1 %/h
            sampleDevice("DEV-1057", tier = "low", energyMah = 232.0),                       // 4.6 %/h
            sampleDevice("DEV-1231", tier = "high", energyMah = 233.0),                      // 4.7 %/h
            sampleDevice("DEV-0418", tier = "high", energyMah = 278.0),                      // 5.56 %/h
            sampleDevice("DEV-1142", tier = "high", energyMah = 279.0),                      // 5.58 %/h
            sampleDevice("DEV-0800", tier = "low", energyMah = 296.0),                       // 5.92 %/h
            sampleDevice("DEV-0204", tier = "mid", energyMah = 316.0),                       // 6.32 %/h
            sampleDevice("DEV-1514", tier = "low", energyMah = 371.0, jankPct = 2.4),        // 7.42 %/h (Fronteira leve!)
            sampleDevice("DEV-1625", tier = "low", energyMah = 379.0, jankPct = 2.9),        // 7.58 %/h (Fronteira leve!)
            sampleDevice("DEV-1711", tier = "high", energyMah = 484.0),                      // 9.68 %/h
            sampleDevice("DEV-1559", tier = "high", energyMah = 503.0),                      // 10.06 %/h
            sampleDevice("DEV-1582", tier = "low", energyMah = 511.0),                       // 10.22 %/h
            sampleDevice("DEV-1767", tier = "mid", energyMah = 540.0),                       // 10.80 %/h
            sampleDevice("DEV-0522", tier = "low", energyMah = 670.0, jankPct = 9.8, crashes = 1), // 13.40 %/h (Crítico)
            sampleDevice("DEV-1985", tier = "low", energyMah = 675.0),                       // 13.50 %/h (Crítico)
            sampleDevice("DEV-1839", tier = "mid", energyMah = 970.0)                        // 19.40 %/h (Crítico)
        )

        val percentileClassifier = FleetPercentileClassifier(fleet)

        // 1. Limiar aprendido P75 fica acima de 7.6 %/h, adaptando-se à frota
        assertTrue(percentileClassifier.learnedWarningDrainThreshold > 7.6)

        // 2. Ambos concordam em 100% nos registros defeituosos (INDETERMINADO)
        val dev0311 = fleet.first { it.id == "DEV-0311" }
        assertEquals(DeviceStatus.INDETERMINADO, referenceClassifier(dev0311).status)
        assertEquals(DeviceStatus.INDETERMINADO, percentileClassifier(dev0311).status)

        // 3. Divergência construtiva: DEV-1514 (7.42 %/h, 0 crashes, 2.4% jank)
        // A Regra Fixa dispara ATENCAO (>= 7.0%/h), enquanto o Percentil reconhece como OK (< P75)
        val dev1514 = fleet.first { it.id == "DEV-1514" }
        assertEquals(DeviceStatus.ATENCAO, referenceClassifier(dev1514).status)
        assertEquals(DeviceStatus.OK, percentileClassifier(dev1514).status)

        // 4. Ambos concordam nos casos severos (DEV-1839 com 19.4 %/h -> CRITICO)
        val dev1839 = fleet.first { it.id == "DEV-1839" }
        assertEquals(DeviceStatus.CRITICO, referenceClassifier(dev1839).status)
        assertEquals(DeviceStatus.CRITICO, percentileClassifier(dev1839).status)
    }

    @Test
    fun `modelo hibrido com teto fisico - impede falsos bons quando a frota inteira sofre degradacao simultanea`() {
        // Simula uma frota inteira degradada (todos os aparelhos drenando entre 15.0 %/h e 22.0 %/h)
        // capacityMah = 5000, screenMinutes = 60 -> 750 mAh = 15.0%/h, 800 mAh = 16.0%/h, ..., 1100 mAh = 22.0%/h
        val degradedFleet = listOf(
            sampleDevice("DEG-01", tier = "low", energyMah = 750.0),  // 15.0 %/h
            sampleDevice("DEG-02", tier = "mid", energyMah = 800.0),  // 16.0 %/h
            sampleDevice("DEG-03", tier = "high", energyMah = 850.0), // 17.0 %/h
            sampleDevice("DEG-04", tier = "low", energyMah = 900.0),  // 18.0 %/h
            sampleDevice("DEG-05", tier = "mid", energyMah = 950.0),  // 19.0 %/h
            sampleDevice("DEG-06", tier = "high", energyMah = 1000.0),// 20.0 %/h
            sampleDevice("DEG-07", tier = "low", energyMah = 1050.0), // 21.0 %/h
            sampleDevice("DEG-08", tier = "mid", energyMah = 1100.0)  // 22.0 %/h
        )

        val hybridClassifier = FleetPercentileClassifier(degradedFleet)

        // Sem teto físico, P75 seria ~20.25%/h e P90 seria ~21.3%/h, classificando 16.0%/h como OK ("falso bom").
        // Com as travas físicas (coerceIn), os limiares ficam cravados nos tetos de segurança:
        assertEquals(10.5, hybridClassifier.learnedWarningDrainThreshold, 0.001)
        assertEquals(14.0, hybridClassifier.learnedCriticalDrainThreshold, 0.001)

        // Aparelho com dreno de 16.0 %/h jamais é classificado como OK ("falso bom"), sendo barrado como CRITICO (>= 14.0%/h)
        val dev16Pct = degradedFleet.first { it.id == "DEG-02" }
        assertEquals(DeviceStatus.CRITICO, hybridClassifier(dev16Pct).status)

        // Todos os aparelhos da frota degradada (>= 15.0%/h) são corretamente reportados como CRITICO
        degradedFleet.forEach { device ->
            assertEquals(DeviceStatus.CRITICO, hybridClassifier(device).status)
        }
    }
}