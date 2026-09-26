package com.example.orderexport.service;

/** 取引先に渡す連携ファイル。ファイル名と中身 */
public record ExportFile(String fileName, String content) {
}
