package org.bitseal.crypt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;

import org.bitseal.core.App;
import org.bitseal.data.Address;
import org.bitseal.database.AddressProvider;
import org.bitseal.util.ArrayCopier;
import org.bitseal.util.Base58;
import org.bitseal.util.ByteUtils;
import org.bitseal.util.VarintEncoder;
import org.spongycastle.jce.interfaces.ECPrivateKey;

/**
This class offers methods to generate new Bitmessage addresses, calculate the ripe hash of
a set of public keys, and to calculate the String representation of Bitmessage addresses
from existing data. <br><br>

A pseudocode overview of the address generation process can be found on page 10 of the Bitmessage
technical paper. The paper is available at at https://bitmessage.org/Bitmessage%20Technical%20Paper.pdf <br><br>

The relevant parts of this pseudocode are copied below. <br><br>

To calculate an address: <br><br>

1) private_signing_key = random 32 byte string <br><br>

2) private_encryption_key = random 32 byte string <br><br>

3) public_signing_key = calculate public key from private_signing_key <br><br>

4) public_encryption_key = calculate public key from private_encryption_key <br><br>

5) hash = RIPEMD160(SHA512(public_signing_key || public_encryption_key) <br><br>

6) checksum = first four bytes of SHA512(SHA512(addressversion || streamnumber|| hash)) <br><br>

7) address = base58encode(addressversion || streamnumber || hash || checksum) <br><br>

@author Jonathan Coe
 */
public class AddressGenerator
{
	// The values for address version and  stream number are currently hard-coded. 
	private static final int MY_ADDRESS_VERSION = 4;
	private static final int MY_STREAM_NUMBER = 1;
	
	private static final String DEFAULT_ADDRESS_LABEL = "New address";
	private static final String IMPORTED_ADDRESS_LABEL = "Imported address";
	
	/**
	 * Generates a new Address object representing a Bitmessage address.
	 * 
	 * @return - A new Address object
	 */
	public Address generateAndSaveNewAddress()
	{
		// Generate a new Address
		Address address = generateNewAddress();
		
		// Save the new Address to the database
		AddressProvider addProv = AddressProvider.get(App.getContext());
		long addressId = addProv.addAddress(address);
		
		// Finally, set the Address's ID to the one generated by the database
		address.setId(addressId);
		
		return address;
	}
	
	/**
	 * Recreates the String representation of a Bitmessage address from a given address version, stream number, 
	 * public signing key and encryption key. <br><br>
	 * 
	 * @param addressVersion - An int representing the address version number of the address to be recreated
	 * @param streamNumber - An int representing the stream number of the address to be recreated
	 * @param publicSigningKey - A byte[] containing the private signing key for this address.
	 * @param publicEncryptionKey - A byte[] containing the private encryption key for this address.
	 * 
	 * @return A String representing the recreated Bitmessage address. 
	 */
	public String recreateAddressString(int addressVersion, int streamNumber, byte[] publicSigningKey, byte[] publicEncryptionKey)
	{		
		byte[] ripeHash = calculateRipeHash(publicSigningKey, publicEncryptionKey);
		
		// When creating addresses, any leading zeros should be stripped from the ripe hash
		ripeHash = ByteUtils.stripLeadingZeros(ripeHash);
		
		String addressString = calculateAddressString(addressVersion, streamNumber, ripeHash);
		
		return addressString;
	}
	
	/**
	 * Calculates the ripe hash needed for address generation
	 * 
	 * @param publicSigningKey - A byte[] containing the public signing key to be used
	 * @param publicEncryptionKey - A byte[] containing the public encryption key to be used
	 * 
	 * @return A byte[] containing the generated ripe hash
	 */
	public byte[] calculateRipeHash(byte[] publicSigningKey, byte[] publicEncryptionKey)
	{
		byte[] concatenatedPublicKeys = ByteUtils.concatenateByteArrays(publicSigningKey, publicEncryptionKey);
		
		byte[] ripeHash = SHA512.sha512hash160(concatenatedPublicKeys);
		
		// Remove any leading zeros from the ripe hash
		ripeHash = ByteUtils.stripLeadingZeros(ripeHash);
		
		return ripeHash;
	}
	
	/**
	 * Takes a pair of private keys and re-generates the Bitmessage address that
	 * they correspond to. The imported address is then saved to the database. 
	 * 
	 * @param privateSigningKey -  The private signing key
	 * @param privateEncryptionKey - The private encryption key
	 * 
	 * @return A boolean indicating whether or not the address was successfully imported
	 */
	public Address importAddress (String privateSigningKey, String privateEncryptionKey)
	{
		KeyConverter keyConv = new KeyConverter();
		ECPrivateKey signingKey = keyConv.decodePrivateKeyFromWIF(privateSigningKey);
		ECPrivateKey encryptionKey = keyConv.decodePrivateKeyFromWIF(privateEncryptionKey);
		
		BigInteger signingKeyDValue = signingKey.getD();
		BigInteger encryptionKeyDValue = encryptionKey.getD();
		
		byte[] signingKeyBytes = signingKeyDValue.toByteArray();
		byte[] encryptionKeyBytes = encryptionKeyDValue.toByteArray();
		
		byte[] publicSigningKeyBytes = ECKeyPair.publicKeyFromPrivate(signingKeyDValue);
		byte[] publicEncryptionKeyBytes = ECKeyPair.publicKeyFromPrivate(encryptionKeyDValue);
		
		Address recreatedAddress = createAddressFromKeys(signingKeyBytes, encryptionKeyBytes, publicSigningKeyBytes, publicEncryptionKeyBytes);
		
		recreatedAddress.setLabel(IMPORTED_ADDRESS_LABEL);
		
		// Save the new Address to the database
		AddressProvider addProv = AddressProvider.get(App.getContext());
		long addressId = addProv.addAddress(recreatedAddress);
		
		// Finally, set the Address's ID to the one generated by the database
		recreatedAddress.setId(addressId);
		
		return recreatedAddress;
	}
		
	/**
	 * Generates a new Bitmessage address. 
	 * 
	 * @return An Address object representing the newly generated Bitmessage address
	 */
	private Address generateNewAddress()
	{					
		ECKeyPair signingKeyPair = new ECKeyPair();
		ECKeyPair encryptionKeyPair = new ECKeyPair();

		byte[] privateSigningKey = signingKeyPair.getPrivKey().toByteArray();
		byte[] privateEncryptionKey = encryptionKeyPair.getPrivKey().toByteArray();
		
		byte[] publicSigningKey = signingKeyPair.getPubKey();
		byte[] publicEncryptionKey = encryptionKeyPair.getPubKey();
		
		Address newAddress =  createAddressFromKeys(privateSigningKey, privateEncryptionKey, publicSigningKey, publicEncryptionKey);
		newAddress.setLabel(DEFAULT_ADDRESS_LABEL);
		
		return newAddress;
	}
	
	/**
	 * Takes a set of signing and encryption keys and uses them to create a Bitmessage address.
	 * 
	 * @param privateSigningKey - The private signing key
	 * @param privateEncryptionKey - The private encryption key
	 * @param publicSigningKey - The public signing key
	 * @param publicEncryptionKey - The public encryption key
	 * 
	 * @return The created Address
	 */
	private Address createAddressFromKeys(byte[] privateSigningKey, byte[] privateEncryptionKey, byte[] publicSigningKey, byte[] publicEncryptionKey)
	{
		byte[] ripeHash = calculateRipeHash(publicSigningKey, publicEncryptionKey);
		byte[] tag = calculateAddressTag(MY_ADDRESS_VERSION, MY_STREAM_NUMBER, ripeHash);
		
		String addressString = calculateAddressString(MY_ADDRESS_VERSION, MY_STREAM_NUMBER, ripeHash);

		KeyConverter keyConv = new KeyConverter();
		String wifPrivateSigningKey  = keyConv.encodePrivateKeyToWIF(privateSigningKey);
		String wifPrivateEncryptionKey  = keyConv.encodePrivateKeyToWIF(privateEncryptionKey);

		Address generatedAddress = new Address();
		generatedAddress.setAddress(addressString);
		generatedAddress.setPrivateSigningKey(wifPrivateSigningKey);
		generatedAddress.setPrivateEncryptionKey(wifPrivateEncryptionKey);
		generatedAddress.setRipeHash(ripeHash);
		generatedAddress.setTag(tag);
		
		return generatedAddress; // Note that the ID and correspondingPubkeyId fields of this Address object have not yet been set
	}
	
	/**
	 * Calculates the combined checksum data needed for generating an address
	 * 
	 * @param addressVersion - An int representing the address version of address being generated
	 * @param streamNumber - An int representing the stream number of the address being generated
	 * @param ripeHash - A byte[] containing the ripe hash of the address being generated
	 * 
	 * @return A byte[] containing the combined checksum data
	 */
	private byte[] calculateCombinedChecksumData(int addressVersion, int streamNumber, byte[] ripeHash)
	{
		byte[] addressVersionBytes = VarintEncoder.encode((long) addressVersion);
		byte[] streamNumberBytes = VarintEncoder.encode((long) streamNumber);
		
		byte[] combinedChecksumData = null;
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try 
		{		
			outputStream.write(addressVersionBytes);
			outputStream.write(streamNumberBytes);
			outputStream.write(ripeHash);
		
			combinedChecksumData = outputStream.toByteArray();
			outputStream.close();
		}
		catch (IOException e) 
		{
			throw new RuntimeException("IOException occurred in AddressGenerator.calculateCombinedChecksumData()", e);
		}
		
		return combinedChecksumData;
	}

	/**
	 * Calculates the checksum needed for generating a new address
	 * 
	 * @param combinedChecksumData - The combined checksum data to make the checksum from
	 * 
	 * @return A byte[] containing the calculated checksum
	 */
	private byte[] calculateChecksum(byte[] combinedChecksumData)
	{
		byte[] checksumFullHash = SHA512.doubleHash(combinedChecksumData);
		
		byte[] checksum = ArrayCopier.copyOfRange(checksumFullHash, 0, 4);
		
		return checksum;
	}

	/**
	 * Calculates the String representation of a Bitmessage address from the supplied data.
	 * 
	 * @param addressVersion - An int representing the address version number
	 * @param streamNumber - An int representing the address stream number
	 * @param ripeHash - A byte[] containing the ripe hash of the address
	 * 
	 * @return A String containing the calculated address
	 */
	private String calculateAddressString(int addressVersion, int streamNumber, byte[] ripeHash)
	{
		byte[] combinedChecksumData = calculateCombinedChecksumData(addressVersion, streamNumber, ripeHash);
		
		byte[] checksum = calculateChecksum(combinedChecksumData);
		
		ByteArrayOutputStream outputStream2 = new ByteArrayOutputStream();
		byte[] combinedAddressData = null;
		try 
		{		
			outputStream2.write(combinedChecksumData);
			outputStream2.write(checksum);
		
			combinedAddressData = outputStream2.toByteArray();
			outputStream2.close();
		}
		
		catch (IOException e) 
		{
			throw new RuntimeException("IOException occurred in AddressGenerator.calculateAddressString()", e);
		}
		
		String base58Address = Base58.encode(combinedAddressData);
		String addressString =  "BM-" + base58Address;
		
		return addressString;
	}
	
	/**
	 * Calculates the 'tag' of a given Bitmessage address. The 'tag' is the second
	 * half of the double SHA-512 hash of the combined address data. 
	 * 
	 * @param addressVersion - An int representing the address version number
	 * @param streamNumber - An int representing the address stream number
	 * @param ripeHash - A byte[] containing the ripe hash of the address
	 * 
	 * @return A byte[] containing the Address tag
	 */
	private byte[] calculateAddressTag(int addressVersion, int streamNumber, byte[] ripeHash)
	{
		byte[] addressVersionBytes = VarintEncoder.encode((long) addressVersion);
		byte[] streamNumberBytes = VarintEncoder.encode((long) streamNumber);
		
		byte[] combinedAddressData = null;
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try 
		{		
			outputStream.write(addressVersionBytes);
			outputStream.write(streamNumberBytes);
			outputStream.write(ripeHash);
		
			combinedAddressData = outputStream.toByteArray();
			outputStream.close();
		}
		catch (IOException e) 
		{
			throw new RuntimeException("IOException occurred in AddressGenerator.calculateAddressTag()", e);
		}
		
		byte[] doubleHashOfAddressData = SHA512.doubleHash(combinedAddressData);
		byte[] tag = ArrayCopier.copyOfRange(doubleHashOfAddressData, 32, doubleHashOfAddressData.length);
		
		return tag;
	}
}