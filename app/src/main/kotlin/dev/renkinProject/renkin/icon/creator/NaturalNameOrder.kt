package dev.renkinProject.renkin.icon.creator

// Calendar day sets ("cal_2", "cal_10") must read 1, 2 … 31, not 1, 10, 11 … 2.
internal object NaturalNameOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val left = chunk.findAll(a).map { it.value }.iterator()
        val right = chunk.findAll(b).map { it.value }.iterator()
        while (left.hasNext() && right.hasNext()) {
            val l = left.next()
            val r = right.next()
            val result = if (l[0].isDigit() && r[0].isDigit()) {
                compareNumbers(l, r)
            } else l.compareTo(r)
            if (result != 0) return result
        }
        return when {
            left.hasNext() -> 1
            right.hasNext() -> -1
            else -> a.compareTo(b)
        }
    }

    private fun compareNumbers(a: String, b: String): Int {
        val left = a.trimStart('0')
        val right = b.trimStart('0')
        return if (left.length != right.length) left.length - right.length else left.compareTo(right)
    }
}
