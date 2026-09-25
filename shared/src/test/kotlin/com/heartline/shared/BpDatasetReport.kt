package com.heartline.shared

import com.heartline.shared.bp.BpDataset
import com.heartline.shared.bp.BpEvaluation
import com.heartline.shared.sync.Protocol
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Replays the current algorithm on a dataset exported from the phone (Blood pressure → Share →
 * BP data) and prints the agreement report. Skipped unless BP_DATASET points to the file:
 *
 *     BP_DATASET=/path/heartline-bp.json ./gradlew :shared:test --tests '*BpDatasetReport*' -i
 */
class BpDatasetReport {
    @Test
    fun report() {
        val path = System.getenv("BP_DATASET")
        assumeTrue("BP_DATASET not set", path != null)
        val data = Protocol.json.decodeFromString<BpDataset>(File(path!!).readText())
        for ((name, incremental) in listOf("fixed calibration" to false, "with cuff checks" to true)) {
            val r = BpEvaluation.evaluate(data, incremental = incremental)
            val sys = "SBP %+.1f ± %.1f (MAE %.1f)".format(r.meanDiffSys, r.sdSys, r.maeSys)
            val dia = "DBP %+.1f ± %.1f (MAE %.1f)".format(r.meanDiffDia, r.sdDia, r.maeDia)
            val rest = "within10=${r.within10Percent}%  slope=${"%.2f".format(r.biasSlopeSys)}  beyond=${r.beyondCalibration}"
            println("${name.padEnd(18)} n=${r.count} refused=${r.refused}  $sys  $dia  $rest")
        }
    }
}
