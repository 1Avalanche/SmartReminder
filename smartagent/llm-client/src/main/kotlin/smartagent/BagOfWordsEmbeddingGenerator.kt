package smartagent

import kotlin.math.sqrt

class BagOfWordsEmbeddingGenerator : EmbeddingGenerator {

    override val dimension: Int = 64

    override fun embed(text: String): FloatArray {
        val vector = FloatArray(dimension)

        text.lowercase()
            .split(Regex("\\W+"))
            .filter { it.isNotEmpty() }
            .forEach { word ->
                val index = Math.floorMod(word.hashCode(), dimension)
                vector[index] += 1.0f
            }

        return l2normalize(vector)
    }

    private fun l2normalize(vector: FloatArray): FloatArray {
        val magnitude = sqrt(vector.sumOf { it.toDouble() * it.toDouble() }).toFloat()
        return if (magnitude == 0f) vector else FloatArray(dimension) { vector[it] / magnitude }
    }
}
