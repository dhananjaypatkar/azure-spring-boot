/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */

package com.microsoft.azure.keyvault.spring;

/**
 * Exception class for azure key vault operations runtime exceptions
 * 
 * @author Dhananjay
 *
 **/
public class KeyVaultOperationException extends RuntimeException {

    private static final long serialVersionUID = 8094129073804685707L;

    /**
     * @param message
     */
    public KeyVaultOperationException(final String message) {
        super(message);
    }

    /**
     * @param message
     * @param exception
     */
    public KeyVaultOperationException(final String message, final Throwable exception) {
        super(message, exception);
    }

}
