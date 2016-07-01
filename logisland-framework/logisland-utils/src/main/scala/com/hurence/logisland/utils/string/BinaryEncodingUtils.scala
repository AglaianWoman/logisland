/*
 Copyright 2016 Hurence

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.hurence.logisland.utils.string

import org.apache.commons.codec.binary.Base64

/**
  * Utility class to encode/decode bytes[]
  */
object BinaryEncodingUtils {

    def encode(content:Array[Byte]) : String = {
        new String(Base64.encodeBase64(content), "UTF-8")//.substring(4)
    }

    def decode(content:String) : Array[Byte] = {
       Base64.decodeBase64(content)
    }
}
