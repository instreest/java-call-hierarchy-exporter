/*
 * Copyright 2026 the java-call-hierarchy-exporter authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jche.external;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * classファイルの定数プールを読み、参照しているメソッドを列挙する。
 *
 * 命令列（Code属性）は読まないので「どのメソッドから呼んでいるか」までは
 * 分からないが、「どのクラスが参照しているか」は分かる。
 * 改修時の影響調査にはこの粒度で足りることが多く、
 * ASMやBCELといった外部ライブラリを持ち込まずに実装できる利点がある。
 */
final class ClassFileRefs {

    /** 定数プールに現れた参照先メソッド1件 */
    record MethodEntry(String ownerFqn, String name, String paramSig) {
    }

    /** 参照している自分のクラス名（FQN） */
    final String thisClass;
    final List<MethodEntry> methodRefs = new ArrayList<>();

    private ClassFileRefs(String thisClass) {
        this.thisClass = thisClass;
    }

    static ClassFileRefs parse(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("classファイルではありません");
        }
        in.readUnsignedShort();     // minor
        in.readUnsignedShort();     // major
        int count = in.readUnsignedShort();

        int[] tags = new int[count];
        String[] utf8 = new String[count];
        int[] refA = new int[count];   // Class:name_index / Ref:class_index / NameAndType:name_index
        int[] refB = new int[count];   // Ref:name_and_type_index / NameAndType:descriptor_index

        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();                            // Utf8
                case 7 -> refA[i] = in.readUnsignedShort();                  // Class
                case 8, 16, 19, 20 -> in.readUnsignedShort();                // String / MethodType / Module / Package
                case 15 -> {                                                 // MethodHandle
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                case 3, 4 -> in.readInt();                                   // Integer / Float
                case 5, 6 -> {                                               // Long / Double
                    in.readLong();
                    i++;   // 8バイト定数は2スロット占有する（ここを飛ばさないと全体がずれる）
                }
                case 9, 10, 11, 12, 17, 18 -> {   // Field/Method/InterfaceMethodref, NameAndType, Dynamic/InvokeDynamic
                    refA[i] = in.readUnsignedShort();
                    refB[i] = in.readUnsignedShort();
                }
                default -> throw new IOException("未知の定数プールタグ: " + tag);
            }
        }

        in.readUnsignedShort();                 // access_flags
        int thisClassIdx = in.readUnsignedShort();
        String thisName = (thisClassIdx > 0 && thisClassIdx < count)
                ? internalToFqn(utf8[refA[thisClassIdx]]) : "(不明)";

        ClassFileRefs out = new ClassFileRefs(thisName);
        for (int i = 1; i < count; i++) {
            // Methodref / InterfaceMethodref のみ（Fieldrefは対象外）
            if (tags[i] != 10 && tags[i] != 11) {
                continue;
            }
            int classIdx = refA[i];
            int natIdx = refB[i];
            if (classIdx <= 0 || natIdx <= 0 || classIdx >= count || natIdx >= count) {
                continue;
            }
            String owner = internalToFqn(utf8[refA[classIdx]]);
            String name = utf8[refA[natIdx]];
            String desc = utf8[refB[natIdx]];
            if (owner == null || name == null || desc == null) {
                continue;
            }
            out.methodRefs.add(new MethodEntry(owner, name, String.join(",", parseParams(desc))));
        }
        return out;
    }

    static String internalToFqn(String internal) {
        return (internal == null) ? null : internal.replace('/', '.');
    }

    /** ディスクリプタ "(Ljava/lang/String;I[Z)V" から引数型のFQN列を取り出す */
    static List<String> parseParams(String desc) {
        List<String> out = new ArrayList<>();
        if (desc == null) {
            return out;
        }
        int i = desc.indexOf('(');
        if (i < 0) {
            return out;
        }
        i++;
        while (i < desc.length() && desc.charAt(i) != ')') {
            int arrayDepth = 0;
            while (i < desc.length() && desc.charAt(i) == '[') {
                arrayDepth++;
                i++;
            }
            if (i >= desc.length()) {
                break;
            }
            String type;
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) {
                    break;
                }
                type = desc.substring(i + 1, end).replace('/', '.');
                i = end + 1;
            } else {
                type = primitiveName(c);
                if (type == null) {
                    return out;
                }
                i++;
            }
            out.add(type + "[]".repeat(arrayDepth));
        }
        return out;
    }

    private static String primitiveName(char descriptor) {
        return switch (descriptor) {
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'D' -> "double";
            case 'F' -> "float";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'S' -> "short";
            case 'Z' -> "boolean";
            default -> null;
        };
    }
}
