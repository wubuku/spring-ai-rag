package com.springairag.core.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestMaskDebug {
    public static void main(String[] args) {
        // Test the SQL pattern directly
        String sqlInput = "SELECT * FROM users WHERE username='admin' AND password='secret'";
        Pattern sqlPattern = Pattern.compile("\\b(password|passwd|pwd|pass)\\s*=\\s*'[^']*'", Pattern.CASE_INSENSITIVE);
        Matcher sqlMatcher = sqlPattern.matcher(sqlInput);
        boolean sqlMatch = sqlMatcher.find();
        System.out.println("SQL pattern matches: " + sqlMatch + " -> " + (sqlMatch ? sqlMatcher.group() : "N/A"));

        // Test pass=secret
        Pattern kvPattern = Pattern.compile("\\b(password|passwd|pwd|pass)\\s*=\\s*[^\\s\"',;}]+", Pattern.CASE_INSENSITIVE);
        String kvInput = "pass=secret";
        Matcher kvMatcher = kvPattern.matcher(kvInput);
        boolean kvMatch = kvMatcher.find();
        System.out.println("KV pattern 'pass=secret' matches: " + kvMatch + " -> " + (kvMatch ? kvMatcher.group() : "N/A"));

        // Test key=api-key-123
        Pattern keyPattern = Pattern.compile("\\b(key|private_key)\\s*=\\s*(sk-|ak-|token-|bearer-|eyJ)[A-Za-z0-9\\-_\\.+=/]{10,}", Pattern.CASE_INSENSITIVE);
        String keyInput = "key=api-key-123";
        Matcher keyMatcher = keyPattern.matcher(keyInput);
        boolean keyMatch = keyMatcher.find();
        System.out.println("Key pattern 'key=api-key-123' matches: " + keyMatch + " -> " + (keyMatch ? keyMatcher.group() : "N/A"));

        // Test current converter
        System.out.println("\nConverter tests (using CURRENT compiled class):");
        System.out.println("SQL IN:  [" + sqlInput + "]");
        System.out.println("SQL OUT: [" + SensitiveDataMaskingConverter.maskSensitiveData(sqlInput) + "]");
        System.out.println("PASS IN:  [pass=secret]");
        System.out.println("PASS OUT: [" + SensitiveDataMaskingConverter.maskSensitiveData("pass=secret") + "]");
        System.out.println("KEY IN:  [key=api-key-123]");
        System.out.println("KEY OUT: [" + SensitiveDataMaskingConverter.maskSensitiveData("key=api-key-123") + "]");
        System.out.println("JSON PLAIN IN:  [User login: {\"password\":\"secret123\"}]");
        System.out.println("JSON PLAIN OUT: [" + SensitiveDataMaskingConverter.maskSensitiveData("User login: {\"password\":\"secret123\"}") + "]");
        System.out.println("JSON ESCAPED IN:  [User login: {\\\"password\\\":\\\"secret123\\\"}]");
        System.out.println("JSON ESCAPED OUT: [" + SensitiveDataMaskingConverter.maskSensitiveData("User login: {\\\"password\\\":\\\"secret123\\\"}") + "]");
    }
}
