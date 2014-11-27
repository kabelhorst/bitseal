/** 
CacheWord License:
This file contains the license for CacheWord.
For more information about CacheWord, see https://guardianproject.info/
If you got this file as a part of a larger bundle, there may be other
license terms that you should be aware of.
===============================================================================
CacheWord is distributed under this license (aka the 3-clause BSD license)
Copyright (C) 2013-2014 Abel Luck <abel@guardianproject.info>
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:
* Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
* Neither the names of the copyright owners nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.
THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. 
*/
package org.bitseal.database;

import info.guardianproject.cacheword.CacheWordHandler;

import java.lang.reflect.Method;
import java.nio.CharBuffer;

import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteDatabase.CursorFactory;
import net.sqlcipher.database.SQLiteException;
import net.sqlcipher.database.SQLiteOpenHelper;
import android.content.Context;
import android.util.Log;

/**
 * A helper class to manage database creation and version management. You create
 * a subclass implementing {@link #onCreate}, {@link #onUpgrade} and optionally
 * {@link #onOpen}, and this class takes care of opening the database if it
 * exists, creating it if it does not, and upgrading it as necessary.
 * Transactions are used to make sure the database is always in a sensible
 * state.
 * <p>
 * For an example, see the NotePadProvider class in the NotePad sample
 * application, in the <em>samples/</em> directory of the SDK.
 * </p>
 * 
 * @author Abel Luck, modified by Jonathan Coe
 */
public abstract class SQLCipherOpenHelper extends SQLiteOpenHelper
{
    private static final String TAG = "SQLCipherOpenHelper";

    protected Context mContext; // shame we have to duplicate this here
    private CacheWordHandler mCacheWordHandler;

    public SQLCipherOpenHelper(CacheWordHandler cacheWordHandler, Context context, String name, CursorFactory factory, int version) 
    {
        super(context, name, factory, version, new SQLCipherV3MigrationHook(context));
        
        if (cacheWordHandler == null)
        {
        	throw new IllegalArgumentException("CacheWordHandler is null");
        }
        
		mCacheWordHandler = cacheWordHandler;
		mCacheWordHandler.connectToService();
	    
		Log.d(TAG, "TEMPORARY: SQLCipherOpenHelper constructor method called");
    }

    /**
     * Create and/or open a database that will be used for reading and writing.
     * Once opened successfully, the database is cached, so you can call this
     * method every time you need to write to the database. Make sure to call
     * {@link #close} when you no longer need it.
     * <p>
     * Errors such as bad permissions or a full disk may cause this operation to
     * fail, but future attempts may succeed if the problem is fixed.
     * </p>
     *
     * @throws SQLiteException if the database cannot be opened for writing
     * @return a read/write database object valid until {@link #close} is called
     */
    public synchronized SQLiteDatabase getWritableDatabase()
    {
    	Log.d(TAG, "TEMPORARY: SQLCiperOpenHelper.getWritableDatabase() called.");
    	
    	if (mCacheWordHandler.isLocked())
        {
    		throw new SQLiteException("Database locked. Decryption key unavailable.");
        }
        
        return super.getWritableDatabase(encodeRawKey(mCacheWordHandler.getEncryptionKey()));
    }
    
    /**
     * Create and/or opens an unencrypted SQLiteDatabase
     *
     * @throws SQLiteException if the database cannot be opened for writing
     * 
     * @return a read/write database object valid until {@link #close} is called
     */
    public synchronized SQLiteDatabase getUnencryptedDatabase()
    {
    	Log.d(TAG, "TEMPORARY: SQLCiperOpenHelper.getUnencryptedDatabase() called.");
        
        return super.getWritableDatabase("");
    }

    /**
     * Create and/or open a database. This will be the same object returned by
     * {@link #getWritableDatabase} unless some problem, such as a full disk,
     * requires the database to be opened read-only. In that case, a read-only
     * database object will be returned. If the problem is fixed, a future call
     * to {@link #getWritableDatabase} may succeed, in which case the read-only
     * database object will be closed and the read/write object will be returned
     * in the future.
     *
     * @throws SQLiteException if the database cannot be opened
     * @return a database object valid until {@link #getWritableDatabase} or
     *         {@link #close} is called.
     */
    public synchronized SQLiteDatabase getReadableDatabase()
    {
    	if (mCacheWordHandler.isLocked())
        {
        	throw new SQLiteException("Database locked. Decryption key unavailable.");
        }
        
        return super.getReadableDatabase(encodeRawKey(mCacheWordHandler.getEncryptionKey()));
    }

    /**
     * Formats a byte sequence into the literal string format expected by
     * SQLCipher: hex'HEX ENCODED BYTES' The key data must be 256 bits (32
     * bytes) wide. The key data will be formatted into a 64 character hex
     * string with a special prefix and suffix SQLCipher uses to distinguish raw
     * key data from a password.
     *
     * @link http://sqlcipher.net/sqlcipher-api/#key
     * @param raw_key a 32 byte array
     * @return the encoded key
     */
    public static char[] encodeRawKey(byte[] raw_key)
    {
        if (raw_key.length != 32)
        {
        	throw new IllegalArgumentException("provided key not 32 bytes (256 bits) wide");
        } 

        final String kPrefix;
        final String kSuffix;

        if (sqlcipher_uses_native_key)
        {
            kPrefix = "x'";
            kSuffix = "'";
        } 
        else
        {
            Log.d(TAG, "sqlcipher uses PRAGMA to set key - SPECIAL HACK IN PROGRESS");
            kPrefix = "x''";
            kSuffix = "''";
        }
        final char[] key_chars = encodeHex(raw_key, HEX_DIGITS_LOWER);
        if (key_chars.length != 64)
        {
        	throw new IllegalStateException("encoded key is not 64 bytes wide");
        }  

        char[] kPrefix_c = kPrefix.toCharArray();
        char[] kSuffix_c = kSuffix.toCharArray();
        CharBuffer cb = CharBuffer.allocate(kPrefix_c.length + kSuffix_c.length + key_chars.length);
        cb.put(kPrefix_c);
        cb.put(key_chars);
        cb.put(kSuffix_c);

        return cb.array();
    }

    /**
     * @see #encodeRawKey(byte[])
     */
    public static String encodeRawKeyToStr(byte[] raw_key)
    {
        return new String(encodeRawKey(raw_key));
    }

    /*
     * Special hack for detecting whether or not we're using a new SQLCipher for
     * Android library The old version uses the PRAGMA to set the key, which
     * requires escaping of the single quote characters. The new version calls a
     * native method to set the key instead.
     * @see https://github.com/sqlcipher/android-database-sqlcipher/pull/95
     */
    private static final boolean sqlcipher_uses_native_key = check_sqlcipher_uses_native_key();

    private static boolean check_sqlcipher_uses_native_key()
    {
        for (Method method : SQLiteDatabase.class.getDeclaredMethods())
        {
            if (method.getName().equals("native_key"))
            {
            	return true;
            }         
        }
        return false;
    }

    private static final char[] HEX_DIGITS_LOWER = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    private static char[] encodeHex(final byte[] data, final char[] toDigits) 
    {
        final int l = data.length;
        final char[] out = new char[l << 1];
        // two characters form the hex value.
        for (int i = 0, j = 0; i < l; i++)
        {
            out[j++] = toDigits[(0xF0 & data[i]) >>> 4];
            out[j++] = toDigits[0x0F & data[i]];
        }
        return out;
    }
}