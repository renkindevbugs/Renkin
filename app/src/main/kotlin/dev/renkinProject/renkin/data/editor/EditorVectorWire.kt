package dev.renkinProject.renkin.data.editor

import androidx.compose.ui.graphics.vector.PathNode

internal fun EditorArtworkWriter.vectorNode(value: EditorVectorNode, depth: Int = 0) {
    budget.node(depth)
    when (value) {
        is EditorVectorGroup -> {
            output.writeByte(1); text(value.name)
            listOf(value.rotation, value.pivotX, value.pivotY, value.scaleX, value.scaleY, value.translationX, value.translationY).forEach(::number)
            commands(value.clip)
            require(value.children.size <= EditorArtworkBudget.MAX_NODES)
            output.writeInt(value.children.size); value.children.forEach { vectorNode(it, depth + 1) }
        }
        is EditorVectorPath -> {
            output.writeByte(2); text(value.name); commands(value.commands); boolean(value.evenOdd)
            brush(value.fill); number(value.fillAlpha); brush(value.stroke); number(value.strokeAlpha)
            number(value.strokeWidth); text(value.cap); text(value.join)
            listOf(value.miter, value.trimStart, value.trimEnd, value.trimOffset).forEach(::number)
        }
    }
}

internal fun EditorArtworkReader.vectorNode(depth: Int = 0): EditorVectorNode {
    budget.node(depth)
    return when (input.readUnsignedByte()) {
        1 -> {
            val name = text(); val transform = List(7) { number() }; val clip = commands()
            val children = List(count(EditorArtworkBudget.MAX_NODES)) { vectorNode(depth + 1) }
            EditorVectorGroup(name, transform[0], transform[1], transform[2], transform[3], transform[4], transform[5], transform[6], clip, children)
        }
        2 -> EditorVectorPath(text(), commands(), boolean(), brush(), number(), brush(), number(), number(), text(), text(), number(), number(), number(), number())
        else -> throw IllegalArgumentException("Unknown editor vector node")
    }
}

private fun EditorArtworkWriter.brush(value: EditorVectorBrush?) {
    boolean(value != null); value?.let { output.writeLong(it.colorBits); optionalText(it.reference) }
}
private fun EditorArtworkReader.brush(): EditorVectorBrush? = if (boolean()) EditorVectorBrush(input.readLong(), optionalText()) else null

private fun EditorArtworkWriter.commands(values: List<PathNode>) {
    budget.commands(values.size); output.writeInt(values.size)
    for (value in values) {
        ensureActive()
        when (value) {
            PathNode.Close -> output.writeByte('Z'.code)
            is PathNode.MoveTo -> command('M', value.x, value.y)
            is PathNode.RelativeMoveTo -> command('m', value.dx, value.dy)
            is PathNode.LineTo -> command('L', value.x, value.y)
            is PathNode.RelativeLineTo -> command('l', value.dx, value.dy)
            is PathNode.HorizontalTo -> command('H', value.x)
            is PathNode.RelativeHorizontalTo -> command('h', value.dx)
            is PathNode.VerticalTo -> command('V', value.y)
            is PathNode.RelativeVerticalTo -> command('v', value.dy)
            is PathNode.CurveTo -> command('C', value.x1, value.y1, value.x2, value.y2, value.x3, value.y3)
            is PathNode.RelativeCurveTo -> command('c', value.dx1, value.dy1, value.dx2, value.dy2, value.dx3, value.dy3)
            is PathNode.ReflectiveCurveTo -> command('S', value.x1, value.y1, value.x2, value.y2)
            is PathNode.RelativeReflectiveCurveTo -> command('s', value.dx1, value.dy1, value.dx2, value.dy2)
            is PathNode.QuadTo -> command('Q', value.x1, value.y1, value.x2, value.y2)
            is PathNode.RelativeQuadTo -> command('q', value.dx1, value.dy1, value.dx2, value.dy2)
            is PathNode.ReflectiveQuadTo -> command('T', value.x, value.y)
            is PathNode.RelativeReflectiveQuadTo -> command('t', value.dx, value.dy)
            is PathNode.ArcTo -> {
                command('A', value.horizontalEllipseRadius, value.verticalEllipseRadius, value.theta)
                boolean(value.isMoreThanHalf); boolean(value.isPositiveArc); number(value.arcStartX); number(value.arcStartY)
            }
            is PathNode.RelativeArcTo -> {
                command('a', value.horizontalEllipseRadius, value.verticalEllipseRadius, value.theta)
                boolean(value.isMoreThanHalf); boolean(value.isPositiveArc); number(value.arcStartDx); number(value.arcStartDy)
            }
        }
    }
}

private fun EditorArtworkWriter.command(tag: Char, vararg values: Float) { output.writeByte(tag.code); values.forEach(::number) }
private fun EditorArtworkReader.commands(): List<PathNode> {
    val count = count(EditorArtworkBudget.MAX_COMMANDS); budget.commands(count)
    return List(count) {
        ensureActive()
        when (input.readUnsignedByte().toChar()) {
            'Z' -> PathNode.Close
            'M' -> PathNode.MoveTo(number(), number())
            'm' -> PathNode.RelativeMoveTo(number(), number())
            'L' -> PathNode.LineTo(number(), number())
            'l' -> PathNode.RelativeLineTo(number(), number())
            'H' -> PathNode.HorizontalTo(number())
            'h' -> PathNode.RelativeHorizontalTo(number())
            'V' -> PathNode.VerticalTo(number())
            'v' -> PathNode.RelativeVerticalTo(number())
            'C' -> PathNode.CurveTo(number(), number(), number(), number(), number(), number())
            'c' -> PathNode.RelativeCurveTo(number(), number(), number(), number(), number(), number())
            'S' -> PathNode.ReflectiveCurveTo(number(), number(), number(), number())
            's' -> PathNode.RelativeReflectiveCurveTo(number(), number(), number(), number())
            'Q' -> PathNode.QuadTo(number(), number(), number(), number())
            'q' -> PathNode.RelativeQuadTo(number(), number(), number(), number())
            'T' -> PathNode.ReflectiveQuadTo(number(), number())
            't' -> PathNode.RelativeReflectiveQuadTo(number(), number())
            'A' -> PathNode.ArcTo(number(), number(), number(), boolean(), boolean(), number(), number())
            'a' -> PathNode.RelativeArcTo(number(), number(), number(), boolean(), boolean(), number(), number())
            else -> throw IllegalArgumentException("Unknown editor path command")
        }
    }
}
