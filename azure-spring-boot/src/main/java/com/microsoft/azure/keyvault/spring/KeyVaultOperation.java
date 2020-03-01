/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */

package com.microsoft.azure.keyvault.spring;

import com.azure.core.http.rest.PagedIterable;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.security.keyvault.secrets.models.SecretProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
public class KeyVaultOperation {
    private final long cacheRefreshIntervalInMs;
    private final List<String> secretKeys;
    private final List<String> secretKeysFile;


    private final Object refreshLock = new Object();
    private final SecretClient keyVaultClient;
    private final String vaultUri;

    private ArrayList<String> propertyNames = new ArrayList<>(); 
    private String[] propertyNamesArr;

    // Added for file based azure key vault secrets..
    private Properties fileProperties = new Properties();

    private final AtomicLong lastUpdateTime = new AtomicLong();
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    public KeyVaultOperation(final SecretClient keyVaultClient, String vaultUri, final long refreshInterval,
            final List<String> secretKeys, final List<String> secretKeysFile) {
        this.cacheRefreshIntervalInMs = refreshInterval;
        this.secretKeys = secretKeys;
        this.secretKeysFile = secretKeysFile;
        this.keyVaultClient = keyVaultClient;
        // TODO(pan): need to validate why last '/' need to be truncated.
        this.vaultUri = StringUtils.trimTrailingCharacter(vaultUri.trim(), '/');
        fillSecretsList();
    }

    public String[] list() {
        try {
            this.rwLock.readLock().lock();
            return propertyNamesArr;
        } finally {
            this.rwLock.readLock().unlock();
        }
    }

    private String getKeyvaultSecretName(@NonNull String property) {
        if (property.matches("[a-z0-9A-Z-]+")) {
            return property.toLowerCase(Locale.US);
        } else if (property.matches("[A-Z0-9_]+")) {
            return property.toLowerCase(Locale.US).replaceAll("_", "-");
        } else {
            return property.toLowerCase(Locale.US).replaceAll("-", "") // my-project -> myproject
                    .replaceAll("_", "") // my_project -> myproject
                    .replaceAll("\\.", "-"); // acme.myproject -> acme-myproject
        }
    }

    /**
     * For convention we need to support all relaxed binding format from spring,
     * these may include:
     * <table>
     * <tr>
     * <td>Spring relaxed binding names</td>
     * </tr>
     * <tr>
     * <td>acme.my-project.person.first-name</td>
     * </tr>
     * <tr>
     * <td>acme.myProject.person.firstName</td>
     * </tr>
     * <tr>
     * <td>acme.my_project.person.first_name</td>
     * </tr>
     * <tr>
     * <td>ACME_MYPROJECT_PERSON_FIRSTNAME</td>
     * </tr>
     * </table>
     * But azure keyvault only allows ^[0-9a-zA-Z-]+$ and case insensitive, so there
     * must be some conversion between spring names and azure keyvault names. For
     * example, the 4 properties stated above should be convert to
     * acme-myproject-person-firstname in keyvault.
     *
     * @param property of secret instance.
     * @return the value of secret with given name or null.
     */
    public String get(final String property) {
        Assert.hasText(property, "property should contain text.");
        final String secretName = getKeyvaultSecretName(property);

        // if user don't set specific secret keys, then refresh token
        if (this.secretKeys == null || secretKeys.size() == 0) {
            // refresh periodically
            refreshPropertyNames();
        }
        if (this.propertyNames.contains(secretName)) {
            final KeyVaultSecret secret = this.keyVaultClient.getSecret(secretName);
            return secret == null ? null : secret.getValue();
        } else {
            // Check in file based properties.
            if ( this.fileProperties.contains(property)) {
                return this.fileProperties.getProperty(property);
            }
            return null;
        }
    }

    private void refreshPropertyNames() {
        if (System.currentTimeMillis() - this.lastUpdateTime.get() > this.cacheRefreshIntervalInMs) {
            synchronized (this.refreshLock) {
                if (System.currentTimeMillis() - this.lastUpdateTime.get() > 
                this.cacheRefreshIntervalInMs) {
                    this.lastUpdateTime.set(System.currentTimeMillis());
                    fillSecretsList();
                }
            }
        }
    }

    private void fillSecretsList() {
        try {
            this.rwLock.writeLock().lock();
            if (this.secretKeys == null || secretKeys.size() == 0) {
                this.propertyNames.clear();

                final PagedIterable<SecretProperties> secretProperties = 
                        keyVaultClient.listPropertiesOfSecrets();
                secretProperties.forEach(s -> {
                    final String secretName = s.getName().replace(vaultUri + "/secrets/", "");
                    addSecretIfNotExist(secretName);
                });

                this.lastUpdateTime.set(System.currentTimeMillis());
            } else {
                for (final String secretKey : secretKeys) {
                    addSecretIfNotExist(secretKey);
                }
            }
            propertyNamesArr = propertyNames.toArray(new String[0]);
            // Added for loading file based secrets from azure key vault
            if (this.secretKeysFile != null && !this.secretKeysFile.isEmpty()) {

                secretKeysFile.forEach(skf -> {
                    final KeyVaultSecret secret = this.keyVaultClient.getSecret(skf);
                    try {
                        // Load contents of the secret into a local property store..
                        this.fileProperties.load(new StringReader(secret.getValue()));
                    } catch (IOException e) {
                        throw new KeyVaultOperationException(
                    "Exception while loading file based secrets from key vault",
                                e);
                    }
                });

            }

        } finally {
            this.rwLock.writeLock().unlock();
        }
    }

    private void addSecretIfNotExist(final String secretName) {
        final String secretNameLowerCase = secretName.toLowerCase(Locale.US);
        if (!propertyNames.contains(secretNameLowerCase)) {
            propertyNames.add(secretNameLowerCase);
        }
        final String secretNameSeparatedByDot = secretNameLowerCase.replaceAll("-", ".");
        if (!propertyNames.contains(secretNameSeparatedByDot)) {
            propertyNames.add(secretNameSeparatedByDot);
        }
    }

}
