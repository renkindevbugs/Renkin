/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.res2

import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.util.TypedValue
import android.util.Xml
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.compat2.AndroidVectorParser
import androidx.compose.ui.graphics.vector.compat2.createVectorImageBuilder
import androidx.compose.ui.graphics.vector.compat2.isAtEnd
import androidx.compose.ui.graphics.vector.compat2.parseCurrentVectorNode
import androidx.compose.ui.graphics.vector.compat2.seekToStartTag
import java.lang.ref.WeakReference
import org.xmlpull.v1.XmlPullParserException

@Throws(XmlPullParserException::class)
fun ImageVector.Companion.vectorResource(
    theme: Resources.Theme? = null,
    res: Resources,
    resId: Int
): ImageVector {
    val value = TypedValue()
    res.getValue(resId, value, true)

    return loadVectorResourceInner(
        theme,
        res,
        res.getXml(resId).apply { seekToStartTag() },
        value.changingConfigurations
    )
        .imageVector
}

@Throws(XmlPullParserException::class)
fun ImageVector.Companion.vectorResource(
    theme: Resources.Theme? = null,
    res: Resources,
    parser: XmlResourceParser,
): ImageVector {
    return loadVectorResourceInner(
        theme,
        res,
        parser,
        0,
        parser.depth
    )
        .imageVector
}

/**
 * Helper method that parses a vector asset from the given [XmlResourceParser] position. This method
 * assumes the parser is already been positioned to the start tag
 */
@Throws(XmlPullParserException::class)
internal fun loadVectorResourceInner(
    theme: Resources.Theme? = null,
    res: Resources,
    parser: XmlResourceParser,
    changingConfigurations: Int,
    minimumDepth: Int = 0
): ImageVectorCache.ImageVectorEntry {
    val attrs = Xml.asAttributeSet(parser)
    val resourceParser = AndroidVectorParser(parser)
    val builder = resourceParser.createVectorImageBuilder(res, theme, attrs)

    var nestedGroups = 0
    while (!parser.isAtEnd() && parser.depth >= minimumDepth) {
        nestedGroups =
            resourceParser.parseCurrentVectorNode(res, attrs, theme, builder, nestedGroups)
        parser.next()
    }
    return ImageVectorCache.ImageVectorEntry(builder.build(), changingConfigurations)
}

/**
 * Object responsible for caching [ImageVector] instances based on the given theme and drawable
 * resource identifier
 */
internal class ImageVectorCache {

    /** Key that binds the corresponding theme with the resource identifier for the vector asset */
    data class Key(val theme: Resources.Theme, val id: Int)

    /**
     * Tuple that contains the [ImageVector] as well as the corresponding configuration flags that
     * the [ImageVector] depends on. That is if there is a configuration change that updates the
     * parameters in the flag, this vector should be regenerated from the current configuration
     */
    data class ImageVectorEntry(val imageVector: ImageVector, val configFlags: Int)

    private val map = HashMap<Key, WeakReference<ImageVectorEntry>>()

    operator fun get(key: Key): ImageVectorEntry? = map[key]?.get()

    fun prune(configChanges: Int) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val imageVectorEntry = entry.value.get()
            if (
                imageVectorEntry == null ||
                Configuration.needNewResources(configChanges, imageVectorEntry.configFlags)
            ) {
                it.remove()
            }
        }
    }

    operator fun set(key: Key, imageVectorEntry: ImageVectorEntry) {
        map[key] = WeakReference<ImageVectorEntry>(imageVectorEntry)
    }

    fun clear() {
        map.clear()
    }
}