package com.example.baseplate.runtime.crypto;

/** 运维工具：生成可写进配置文件的密文。 */
public final class CiphertextTool {

    private CiphertextTool() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("用法: CiphertextTool <明文> <密钥>");
            System.exit(2);
        }
        System.out.println(AesGcmCipher.encrypt(args[0], args[1]));
    }
}
