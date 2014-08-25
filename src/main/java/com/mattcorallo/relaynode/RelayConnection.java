package com.mattcorallo.relaynode;

import com.google.bitcoin.core.*;
import com.google.bitcoin.net.MessageWriteTarget;
import com.google.bitcoin.net.StreamParser;
import com.google.bitcoin.params.MainNetParams;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;

public abstract class RelayConnection implements StreamParser {
	private static final NetworkParameters params = MainNetParams.get();
	private static final int MAGIC_BYTES = 0xF2BEEF42;

	static enum RelayMode {
		ABBREV_HASH,
		CACHE_ID,
	}

	private static final Map<String, Integer> MAX_RELAY_FREE_TRANSACTION_BYTES = new HashMap<>();
	private static final Map<String, Integer> TRANSACTIONS_CACHED = new HashMap<>();
	private static final Map<String, RelayMode> RELAY_MODE = new HashMap<>();
	static {
		MAX_RELAY_FREE_TRANSACTION_BYTES.put("efficient eagle", Block.MAX_BLOCK_SIZE);
		TRANSACTIONS_CACHED.put("efficient eagle", 2000);
		RELAY_MODE.put("efficient eagle", RelayMode.ABBREV_HASH);

		MAX_RELAY_FREE_TRANSACTION_BYTES.put("charming chameleon", 10000);
		TRANSACTIONS_CACHED.put("charming chameleon", 1000);
		RELAY_MODE.put("charming chameleon", RelayMode.ABBREV_HASH);

		MAX_RELAY_FREE_TRANSACTION_BYTES.put(RelayNode.VERSION, 25000);
		TRANSACTIONS_CACHED.put(RelayNode.VERSION, 1000);
		RELAY_MODE.put(RelayNode.VERSION, RelayMode.CACHE_ID);
	}

	private enum MessageTypes {
		VERSION,
		BLOCK, TRANSACTION, END_BLOCK,
		MAX_VERSION,
	}

	private class PendingBlock {
		Block header;
		@NotNull
		Map<QuarterHash, Transaction> transactions = new LinkedHashMap<>();
		int pendingTransactionCount = 0;
		boolean alreadyBuilt = false;

		PendingBlock(Block header) {
			this.header = header;
		}

		synchronized void addTransaction(QuarterHash hash) {
			Transaction t = relayTransactionCache.get(hash);
			transactions.put(hash, t);
			if (t == null)
				pendingTransactionCount++;
		}

		synchronized void addTransaction(Integer index) {
			Transaction t = newRelayTransactionCache.getByIndex(index);
			newRelayTransactionCache.remove(t);
			transactions.put(new QuarterHash(t.getHash()), t);
		}

		synchronized void foundTransaction(@NotNull Transaction t) throws VerificationException {
			if (transactions.containsKey(new QuarterHash(t.getHash()))) {
				if (transactions.put(new QuarterHash(t.getHash()), t) != null)
					throw new ProtocolException("Duplicate transaction in a single block");

				pendingTransactionCount--;

				if (pendingTransactionCount == 0)
					buildBlock();
				else if (pendingTransactionCount < 0)
					throw new ProtocolException("pendingTransactionCount " + pendingTransactionCount);
			} else if (RELAY_MODE.get(protocolVersion) == RelayMode.CACHE_ID)
				transactions.put(new QuarterHash(t.getHash()), t);
			else
				throw new ProtocolException("foundTransaction we didn't need");
		}

		public void buildBlock() throws VerificationException {
			if (alreadyBuilt)
				return;
			alreadyBuilt = true;

			List<Transaction> txn = new LinkedList<>();
			for (Map.Entry<QuarterHash, Transaction> e : transactions.entrySet())
				txn.add(e.getValue());

			Block block = new Block(params, header.getVersion(), header.getPrevBlockHash(), header.getMerkleRoot(),
					header.getTimeSeconds(), header.getDifficultyTarget(), header.getNonce(),
					txn);

			block.verify();

			receiveBlock(block);

			LogStatsRecv("Block built with " + bytesInBlock + " bytes on the wire");
		}
	}

	private boolean sendVersionOnConnect;
	private String protocolVersion = null;

	private final Set<Sha256Hash> relayedBlockCache = LimitedSynchronizedObjects.createSet(50);

	private volatile ArraySet<Sha256Hash> relayedTransactionCache = null;
	private volatile Map<QuarterHash, Transaction> relayTransactionCache = null;
	private volatile ArraySet<Transaction> newRelayTransactionCache = null;

	private MessageWriteTarget relayPeer;

	public long txnInBlock = 0, txnRelayedInBlock = 0;
	public long bytesInBlock = 0;
	public long txnInBlockTotal = 0, txnSkippedTotal = 0;
	public long txnRelayedOutOfBlockTotal = 0;

	abstract void LogLine(String line);
	abstract void LogStatsRecv(String lines);
	abstract void LogConnected(String line);

	abstract void receiveBlockHeader(Block b);
	abstract void receiveBlock(Block b);
	abstract void receiveTransaction(Transaction t);

	static final Executor sendBlockExecutor = new ThreadPoolExecutor(4, 50, 2, TimeUnit.HOURS, new LinkedBlockingQueue<Runnable>());
	static final Executor sendTransactionExecutor = new ThreadPoolExecutor(4, 25, 2, TimeUnit.HOURS, new LinkedBlockingQueue<Runnable>());

	public RelayConnection(boolean sendVersionOnConnect) {
		this.sendVersionOnConnect = sendVersionOnConnect;
	}

	public void sendBlock(@NotNull final Block b) {
		if (protocolVersion == null)
			return;
		sendBlockExecutor.execute(new Runnable() {
			@Override
			public void run() {
				synchronized (RelayConnection.this) {
					try {
						if (relayedBlockCache.contains(b.getHash()))
							return;

						byte[] blockHeader = b.cloneAsHeader().bitcoinSerialize();
						int transactionCount = b.getTransactions().size();
						RelayMode mode = RELAY_MODE.get(protocolVersion);

						// Guess that we're only gonna relay the coinbase txn
						ByteArrayOutputStream out = new ByteArrayOutputStream(4*4 + blockHeader.length + transactionCount*2 + 3 + b.getTransactions().get(0).getMessageSize());
						out.write(ByteBuffer.allocate(4 * 3 + blockHeader.length).order(ByteOrder.BIG_ENDIAN)
								.putInt(MAGIC_BYTES).putInt(MessageTypes.BLOCK.ordinal())
								.putInt(mode == RelayMode.ABBREV_HASH ? (blockHeader.length + 4 + transactionCount * QuarterHash.BYTE_LENGTH) : transactionCount)
								.put(blockHeader).array());

						if (mode == RelayMode.ABBREV_HASH)
							out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(transactionCount).array());

						for (Transaction t : b.getTransactions()) {
							if (mode == RelayMode.ABBREV_HASH) {
								QuarterHash.writeBytes(t.getHash(), out);
							} else {
								Integer index = relayedTransactionCache.getIndex(t.getHash());
								if (index == null) {
									byte[] transactionBytes = t.bitcoinSerialize();
									if (transactionBytes.length > 16777215) {
										LogLine("Tried to relay block with invalid transaction in it!");
										throw new RuntimeException();
									}
									out.write((byte) 0xff);
									out.write((byte) 0xff);
									out.write(transactionBytes.length >> 16);
									out.write(transactionBytes.length >>  8);
									out.write(transactionBytes.length      );
									out.write(transactionBytes);
								} else {
									if (index >= Short.MAX_VALUE * 2) {
										LogLine("INTERNAL ERROR: ArraySet is inconsistent");
										relayPeer.closeConnection();
										return;
									}
									out.write(index >> 8);
									out.write(index     );
									relayedTransactionCache.remove(t.getHash());
								}
							}
						}

						relayPeer.writeBytes(out.toByteArray());

						if (mode == RelayMode.ABBREV_HASH) {
							for (Transaction t : b.getTransactions()) {
								if (!relayedTransactionCache.contains(t.getHash())) {
									byte[] transactionBytes = t.bitcoinSerialize();
									relayPeer.writeBytes(ByteBuffer.allocate(4 + transactionBytes.length).order(ByteOrder.BIG_ENDIAN)
											.putInt(transactionBytes.length).put(transactionBytes)
											.array());
								}
							}
						}

						relayPeer.writeBytes(ByteBuffer.allocate(4 * 3).order(ByteOrder.BIG_ENDIAN)
								.putInt(MAGIC_BYTES).putInt(MessageTypes.END_BLOCK.ordinal()).putInt(0)
								.array());

						relayedBlockCache.add(b.getHash());
					} catch (IOException e) {
						/* Should get a disconnect automatically */
						LogLine("Failed to write bytes");
					}
				}
			}
		});
	}

	public void sendTransaction(@NotNull final Transaction t) {
		final byte[] transactionBytes = t.bitcoinSerialize();
		if (protocolVersion == null || transactionBytes.length > MAX_RELAY_FREE_TRANSACTION_BYTES.get(protocolVersion))
			return;
		sendTransactionExecutor.execute(new Runnable(){
			@Override
			public void run(){
				synchronized(RelayConnection.this){
					if (relayedTransactionCache.contains(t.getHash()))
						return;
					try {
						relayPeer.writeBytes(ByteBuffer.allocate(4 * 3 + transactionBytes.length)
								.putInt(MAGIC_BYTES).putInt(MessageTypes.TRANSACTION.ordinal())
								.putInt(transactionBytes.length).put(transactionBytes)
								.array());
					} catch (IOException e) {
						LogLine("Failed to write bytes");
					}
					relayedTransactionCache.add(t.getHash());
				}

			}
		});
	}

	@Nullable
	private PendingBlock readingBlock; private int transactionsLeft;
	@Nullable
	private byte[] readingTransaction; private int readingTransactionPos;

	private int readBlockTransactions(@NotNull ByteBuffer buff) {
		if (readingBlock == null)
			throw new RuntimeException();
		int bytesRead = 0;
		int pos = buff.position();
		RelayMode mode = RELAY_MODE.get(protocolVersion);
		try {
			for (; transactionsLeft > 0; transactionsLeft--) {
				pos = buff.position();
				if (mode == RelayMode.ABBREV_HASH) {
					readingBlock.addTransaction(new QuarterHash(buff));
					bytesRead += QuarterHash.BYTE_LENGTH;
				} else {
					int txIndex = buff.getShort() & 0xffff;
					if (txIndex != 0xffff) {
						readingBlock.addTransaction(txIndex);
						bytesRead += 2;
						bytesInBlock += 2;

						if (transactionsLeft == 1)
							readingBlock.buildBlock();
					} else {
						int txLength = (buff.getShort() & 0xffff) << 8;
						txLength |= buff.get() & 0xff;
						if (txLength > Block.MAX_BLOCK_SIZE)
							throw new ProtocolException("Got txLength of " + txLength);

						readingTransaction = new byte[txLength];
						readingTransactionPos = 0;
						bytesRead += 5;

						txnInBlock++; txnInBlockTotal++;
						bytesInBlock += 2 + 3 + txLength;
						transactionsLeft--;
						break;
					}
				}
				txnInBlock++; txnInBlockTotal++;
			}
		} catch (BufferUnderflowException e) {
			buff.position(pos);
		}
		return bytesRead;
	}

	@Override
	public int receiveBytes(@NotNull ByteBuffer buff) {
		int startPos = buff.position();
		try {
			if (readingTransaction != null) {
				int read = Math.min(readingTransaction.length - readingTransactionPos, buff.remaining());
				buff.get(readingTransaction, readingTransactionPos, read);
				readingTransactionPos += read;
				if (readingTransactionPos == readingTransaction.length) {
					Transaction t = new Transaction(params, readingTransaction);
					t = GlobalObjectTracker.putTransaction(t);
					t.verify();

					if (readingBlock != null) {
						readingBlock.foundTransaction(t);
						LogStatsRecv("Received in-block " + t.getHashAsString() + " size:" + t.getMessageSize());
						txnRelayedInBlock++;
					} else {
						if (RELAY_MODE.get(protocolVersion) == RelayMode.ABBREV_HASH)
							relayTransactionCache.put(new QuarterHash(t.getHash()), t);
						else
							newRelayTransactionCache.add(t);
						receiveTransaction(t);
						txnRelayedOutOfBlockTotal++;
					}

					readingTransaction = null;
					return read + receiveBytes(buff);
				} else
					return read;
			} else if (transactionsLeft > 0) {
				int res = readBlockTransactions(buff);
				if (transactionsLeft <= 0 || readingTransaction != null)
					return res + receiveBytes(buff);
				else
					return res;
			}

			int magic = buff.getInt();
			MessageTypes msgType = MessageTypes.TRANSACTION;
			int msgLength;

			if (readingBlock == null || magic == MAGIC_BYTES) {
				msgType = MessageTypes.values()[buff.getInt()];
				if (readingBlock != null && msgType != MessageTypes.END_BLOCK)
					throw new ProtocolException("Got full message of type " + msgType.name() + " while reading a block");

				msgLength = buff.getInt();
				if (magic != MAGIC_BYTES)
					throw new ProtocolException("Magic bytes incorrect");
			} else
				msgLength = magic;

			if (msgLength > Block.MAX_BLOCK_SIZE)
				throw new ProtocolException("Remote provided message of length " + msgLength);

			switch(msgType) {
				case VERSION:
					byte[] versionBytes = new byte[msgLength];
					buff.get(versionBytes);
					String versionString = new String(versionBytes);

					if (TRANSACTIONS_CACHED.get(versionString) == null) {
						LogLine("Connected to node with bad version: " + versionString.replaceAll("[^ -~]", ""));
						return -1; // Not same version
					} else {
						if (RelayNode.VERSION.equals(versionString))
							LogConnected("Connected to node with version: " + versionString.replaceAll("[^ -~]", ""));
						else
							LogLine("Connected to node with old version: " + versionString.replaceAll("[^ -~]", ""));

						relayedTransactionCache = new ArraySet<>(TRANSACTIONS_CACHED.get(versionString));
						relayTransactionCache = LimitedSynchronizedObjects.createMap(TRANSACTIONS_CACHED.get(versionString));
						newRelayTransactionCache = new ArraySet<>(TRANSACTIONS_CACHED.get(versionString));

						protocolVersion = versionString;

						if (!sendVersionOnConnect) {
							sendVersionMessage(relayPeer, versionString);
							if (!RelayNode.VERSION.equals(versionString))
								relayPeer.writeBytes(ByteBuffer.allocate(4 * 3 + RelayNode.VERSION.length()).order(ByteOrder.BIG_ENDIAN)
										.putInt(MAGIC_BYTES).putInt(MessageTypes.MAX_VERSION.ordinal()).putInt(RelayNode.VERSION.length())
										.put(RelayNode.VERSION.getBytes())
										.array());
						}
					}
					return 3*4 + msgLength;

				case MAX_VERSION:
					versionBytes = new byte[msgLength];
					buff.get(versionBytes);
					versionString = new String(versionBytes);

					LogLine("WARNING: Connected to node with a higher max version (PLEASE UPGRADE): " + versionString.replaceAll("[^ -~]", ""));
					return 3*4 + msgLength;

				case BLOCK:
					if (protocolVersion == null)
						throw new ProtocolException("Got BLOCK before VERSION");
					if (readingBlock != null)
						throw new ProtocolException("readingBlock already present");

					byte[] headerBytes = new byte[Block.HEADER_SIZE];
					buff.get(headerBytes);
					PendingBlock block = new PendingBlock(new Block(params, headerBytes));

					RelayMode mode = RELAY_MODE.get(protocolVersion);

					int transactionCount = mode == RelayMode.ABBREV_HASH ? buff.getInt(): msgLength;
					if (mode == RelayMode.ABBREV_HASH && QuarterHash.BYTE_LENGTH * transactionCount + Block.HEADER_SIZE + 4 != msgLength)
						throw new ProtocolException("transactionCount: " + transactionCount + ", msgLength: " + msgLength);

					receiveBlockHeader(block.header);

					transactionsLeft = transactionCount;
					readingBlock = block;
					bytesInBlock = 4 * 3 + Block.HEADER_SIZE;
					return 4 * (mode == RelayMode.ABBREV_HASH ? 4 : 3) + Block.HEADER_SIZE + receiveBytes(buff);

				case TRANSACTION:
					if (protocolVersion == null)
						throw new ProtocolException("Got TRANSACTION before VERSION");
					if (readingBlock == null && msgLength > MAX_RELAY_FREE_TRANSACTION_BYTES.get(protocolVersion))
						throw new ProtocolException("Too large free transaction relayed");

					readingTransaction = new byte[msgLength];
					readingTransactionPos = 0;
					if (readingBlock == null)
						return 3*4 + receiveBytes(buff);
					else
						return 4 + receiveBytes(buff);

				case END_BLOCK:
					if (protocolVersion == null)
						throw new ProtocolException("Got END_BLOCK before VERSION");
					if (readingBlock == null)
						throw new ProtocolException("END_BLOCK without BLOCK");
					if (readingBlock.pendingTransactionCount > 0)
						throw new ProtocolException("pendingTransactionCount " + readingBlock.pendingTransactionCount);

					readingBlock.buildBlock();

					txnSkippedTotal += (txnInBlock - txnRelayedInBlock);

					LogStatsRecv("Skipped: " + (txnInBlock - txnRelayedInBlock) + "/" + txnInBlock +
							" (" + ((txnInBlock - txnRelayedInBlock + 0.0) / txnInBlock) + "%)\n" +
							"in block " + readingBlock.header.getHashAsString() + "\n" +
							"In total, skipped " + txnSkippedTotal + " of " + txnInBlockTotal +
							" (" + ((txnSkippedTotal + 0.0) / txnInBlockTotal) + "%)\n" +
							"Relayed " + txnRelayedOutOfBlockTotal + " txn out of blocks");

					txnInBlock = 0; txnRelayedInBlock = 0;

					readingBlock = null;
					return 3*4;
			}
		} catch (BufferUnderflowException e) {
			buff.position(startPos);
			return 0;
		} catch (@NotNull NullPointerException | VerificationException | ArrayIndexOutOfBoundsException e) {
			LogLine("Corrupted data read from relay peer " + e.getClass().toString() + ": " + e.getMessage());
			relayPeer.closeConnection();
			return 0;
		} catch (IOException e) {
			LogLine("Failed to write bytes");
			return -1;
		} catch (Exception e) {
			e.printStackTrace();
			e.getCause().printStackTrace();
			throw e;
		}
		throw new RuntimeException();
	}

	private void sendVersionMessage(@NotNull MessageWriteTarget writeTarget, String version) {
		try {
			writeTarget.writeBytes(ByteBuffer.allocate(4 * 3 + version.length())
					.putInt(MAGIC_BYTES).putInt(MessageTypes.VERSION.ordinal()).putInt(version.length())
					.put(version.getBytes())
					.array());
		} catch (IOException e) {
			/* Should get a disconnect automatically */
			LogLine("Failed to write VERSION_INIT");
		}
	}

	@Override
	public void setWriteTarget(@NotNull MessageWriteTarget writeTarget) {
		if (sendVersionOnConnect)
			sendVersionMessage(writeTarget, RelayNode.VERSION);
		relayPeer = writeTarget;
	}

	@Override
	public int getMaxMessageSize() {
		return Block.MAX_BLOCK_SIZE; // Its bigger than 64k, so buffers will just be 64k in size
	}
}