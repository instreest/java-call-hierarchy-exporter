// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { archiveNameFor, urlFor } from '../src/server/jdkDownload';
import { chooseJava, MINIMUM, parseVersion, versionOf } from '../src/server/javaLocator';

// 期待値は Eclipse 版の検査（test/plugin-client/ClientProbe.java）と同じ。
// 2言語の実装がずれたら、どちらかが落ちる

test('版の文字列を読む（1.8 形式と新しい形式）', () => {
    assert.equal(parseVersion('java version "1.8.0_402"'), 8);
    assert.equal(parseVersion('openjdk version "25.0.3" 2026-01-20'), 25);
    assert.equal(parseVersion('openjdk version "17" 2021-09-14'), 17);
    assert.equal(parseVersion('no version here'), 0);
});

test('取得先の URL の組み立て（実際には取りに行かない。閉域でも検査できるように）', () => {
    assert.ok(urlFor(25, 'win32', 'x64').endsWith('/25/ga/windows/x64/jdk/hotspot/normal/eclipse'));
    assert.ok(urlFor(25, 'linux', 'arm64').endsWith('/25/ga/linux/aarch64/jdk/hotspot/normal/eclipse'));
    assert.ok(urlFor(25, 'darwin', 'x64').endsWith('/25/ga/mac/x64/jdk/hotspot/normal/eclipse'));
    assert.equal(archiveNameFor('win32'), 'jdk.zip');
    assert.equal(archiveNameFor('linux'), 'jdk.tar.gz');
});

test('使える JDK を選ぶ（無いものは飛ばす。17 未満しか無ければ選ばない）', () => {
    const java = process.env.JCHE_JAVA;
    assert.ok(java, 'JCHE_JAVA（解析に使う java）を環境変数で渡すこと');
    const version = versionOf(java);
    assert.ok(version >= MINIMUM, `${java} -> Java ${version}`);
    const found = chooseJava(['/no/such/java', java, '/another/missing', undefined]);
    assert.ok(found);
    assert.equal(found.version, version);
    assert.equal(chooseJava(['/no/such/java']), undefined);
});
