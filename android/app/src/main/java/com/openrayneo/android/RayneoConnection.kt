// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

@SuppressLint("MissingPermission") // Activity grants SCAN and CONNECT before invoking this controller.
internal class RayneoConnection(private val context: Context, private val listener: Listener) {
    data class Device(val name: String, val bluetooth: BluetoothDevice, val classicAddress: String?)
    interface Listener {
        fun onDevice(device: Device)
        fun onState(text: String, ready: Boolean, busy: Boolean)
        fun onLog(text: String)
        fun onFrame(frame: ProtocolCodec.Frame, source: String) {}
    }

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var gatt: BluetoothGatt? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var activeScan: ScanCallback? = null
    private var scanTimeout: Runnable? = null
    private var presenceScan: ScanCallback? = null
    private var automaticConnection = false
    private var ready = false
    private var expectedAddress: String? = null
    val resolvedClassicAddress: String? get() = expectedAddress
    private var classicAddress = byteArrayOf()
    private var identity = byteArrayOf()
    private var send: BluetoothGattCharacteristic? = null
    private var pairInfo: BluetoothGattCharacteristic? = null
    private var receiveCharacteristic: BluetoothGattCharacteristic? = null
    private val identityGate = BleIdentityGate()
    private var pairingSequence = 0
    private var notificationSequence = 0
    private var bleDecoder = ProtocolCodec.StreamDecoder()
    private var authenticationHandled = false
    private var connectionPairValue: Byte = 1
    // An authenticated BLE capability request can arrive while SPP opens.
    private var pendingCapability: ProtocolCodec.Frame? = null
    private var bleOnly = false
    private var blePayloadSize = 20
    private data class BleWrite(val bytes: ByteArray, val completion: String?, val callback: ((Result<Unit>) -> Unit)? = null)
    private val bleWrites = ArrayDeque<BleWrite>()
    private var bleWriting = false
    private var bleBusyRetries = 0
    private var bleRetry: Runnable? = null
    private var bleWriteTimeout: Runnable? = null
    private val busyDelays = longArrayOf(80, 160, 320, 640)
    @Volatile private var generation = 0
    private var bondReceiver: BroadcastReceiver? = null
    private var connectionStage = "연결 대기 중"
    private val timeout = Runnable { fail("연결 시간 초과: $connectionStage") }

    fun showBonded() {
        check(adapter.isEnabled) { "먼저 휴대폰 블루투스를 켜세요" }
        adapter.bondedDevices.filter { it.name?.contains("RayNeo", true) == true }.forEach {
            listener.onDevice(Device(it.name ?: "RayNeo iO", it, it.address))
        }
    }

    fun scan() {
        check(adapter.isEnabled) { "먼저 휴대폰 블루투스를 켜세요" }
        check(gatt == null) { "먼저 현재 연결을 끊으세요" }
        stopScan()
        val scanner = checkNotNull(adapter.bluetoothLeScanner) { "블루투스 검색을 사용할 수 없습니다" }
        val callback = newScanCallback()
        activeScan = callback
        state("스캔 중: 안경 찾는 중…", busy = true)
        try {
            scanner.startScan(null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            val expiry = Runnable {
                if (activeScan === callback) {
                    stopScan(); state("스캔 완료, 연결할 안경을 선택하세요")
                }
            }
            scanTimeout = expiry
            main.postDelayed(expiry, 12_000)
        } catch (error: Exception) {
            stopScan()
            state("BLE 스캔 시작 실패: ${error.message ?: error.javaClass.simpleName}")
            throw error
        }
    }

    /** A filtered, low-power presence watch for a previously authenticated device.
     * No connection attempt, bonding, scan timeout or public scan-state events while absent.
     * The service must remain running; a stopped scan cannot deliver a valid match.
     */
    fun watchPresence(device: Device, found: (Device) -> Unit, failed: (String) -> Unit) {
        check(adapter.isEnabled) { "블루투스 꺼짐" }
        check(gatt == null && socket == null) { "기존 연결 정리 대기" }
        stopScan()
        val scanner = checkNotNull(adapter.bluetoothLeScanner)
        val classic = checkNotNull(device.classicAddress)
        val addressBytes = classic.split(':').map { it.toInt(16).toByte() }.toByteArray()
        check(addressBytes.size == 6)
        val filters = listOf(
            ScanFilter.Builder().setDeviceAddress(device.bluetooth.address).build(),
            ScanFilter.Builder().setManufacturerData(0x5716, byteArrayOf(6) + addressBytes).build()
        )
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post {
                    if (presenceScan !== this || activeScan !== this || !result.isConnectable) return@post
                    val data = result.scanRecord?.getManufacturerSpecificData(0x5716)
                    val advertised = data?.takeIf { it.size >= 7 && it[0] == 6.toByte() }?.copyOfRange(1, 7)?.let(::addressString)
                    if (advertised != null && !advertised.equals(classic, true)) return@post
                    if (!result.device.address.equals(device.bluetooth.address, true) && !classic.equals(advertised, true)) return@post
                    // Automatic recovery must never recreate a removed system bond.
                    val bonded = runCatching { adapter.getRemoteDevice(classic).bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
                    if (!bonded) { stopScan(); failed("기존 페어링을 확인해야 합니다"); return@post }
                    stopScan()
                    found(Device(device.name, result.device, classic))
                }
            }
            override fun onScanFailed(errorCode: Int) {
                main.post {
                    if (presenceScan !== this) return@post
                    stopScan(); failed("자동 감지 일시 중단: $errorCode")
                }
            }
        }
        activeScan = callback; presenceScan = callback
        try {
            scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(0L).build(), callback)
        } catch (error: Exception) { stopScan(); throw error }
    }
    fun stopPresenceWatch() { if (presenceScan != null) stopScan() }

    // A stopped scan can still have callbacks queued on Android's main thread.
    private fun newScanCallback() = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (activeScan !== this) return@post
                val record = result.scanRecord
                val name = record?.deviceName ?: result.device.name ?: ""
                val manufacturer = record?.getManufacturerSpecificData(0x5716)
                val matches = name.contains("RayNeo iO", true) ||
                    record?.serviceUuids?.any { it.uuid == SERVICE } == true ||
                    (manufacturer != null && manufacturer.size >= 7 && manufacturer[0] == 6.toByte())
                if (!matches) return@post
                val address = manufacturer?.takeIf { it.size >= 7 && it[0] == 6.toByte() }
                    ?.copyOfRange(1, 7)?.let(::addressString)
                listener.onDevice(Device(name.ifEmpty { "RayNeo iO" }, result.device, address))
            }
        }
        override fun onScanFailed(errorCode: Int) {
            main.post {
                if (activeScan !== this) return@post
                stopScan(); state("BLE 스캔 실패: $errorCode")
            }
        }
    }

    fun connect(device: Device, bleOnly: Boolean = false, automatic: Boolean = false) {
        // Internal cleanup must not publish a second, false disconnect during a connection attempt.
        resetConnection()
        automaticConnection = automatic
        this.bleOnly = bleOnly
        expectedAddress = device.classicAddress
        pairingSequence = 0
        bleDecoder = ProtocolCodec.StreamDecoder()
        // The official SDK and upstream Mac register over BLE before a new
        // system bond. For an existing bond do not create or remove it.
        // openRfcomm verifies/creates the permitted bond after BLE authentication.
        if (automatic && device.classicAddress != null) {
            check(adapter.getRemoteDevice(device.classicAddress).bondState == BluetoothDevice.BOND_BONDED) {
                "기존 페어링이 없어 자동 연결하지 않음"
            }
        }
        startBle(device)
    }

    private fun startBle(device: Device) {
        main.removeCallbacks(timeout)
        state("연결 중: BLE…", busy = true)
        main.postDelayed(timeout, 25_000)
        gatt = device.bluetooth.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK, main)
        check(gatt != null) { "GATT 연결을 만들 수 없음" }
    }

    private fun ensureBond(target: BluetoothDevice, continuation: () -> Unit) {
        if (target.bondState == BluetoothDevice.BOND_BONDED) {
            log("클래식 블루투스 페어링됨")
            continuation()
            return
        }
        check(!automaticConnection) { "기존 페어링이 없어 자동 연결하지 않음" }
        state("휴대폰에서 시스템 블루투스 페어링을 완료하세요…", busy = true)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (bondReceiver !== this || intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (device?.address != target.address) return
                val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                log("클래식 블루투스 페어링 상태=$bond")
                when (bond) {
                    BluetoothDevice.BOND_BONDED -> {
                        stopObservingBond()
                        main.removeCallbacks(timeout)
                        guarded(continuation)
                    }
                    BluetoothDevice.BOND_NONE -> fail("클래식 블루투스 페어링이 완료되지 않았습니다. 안경을 페어링 가능 상태로 두고 다시 연결하세요")
                }
            }
        }
        bondReceiver = receiver
        // Bluetooth broadcasts can originate from the privileged Bluetooth process.
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 45_000)
        if (target.bondState == BluetoothDevice.BOND_BONDED) {
            stopObservingBond()
            main.removeCallbacks(timeout)
            continuation()
        } else if (target.bondState == BluetoothDevice.BOND_NONE) {
            if (!target.createBond()) fail("시스템이 클래식 블루투스 페어링 요청을 받아들이지 않음")
        }
    }

    private fun stopObservingBond() {
        val receiver = bondReceiver ?: return
        bondReceiver = null
        runCatching { context.unregisterReceiver(receiver) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
            if (connection !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail("BLE 연결 종료: status=$status, state=$newState")
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) guarded {
                state("BLE 연결됨, 서비스 탐색 중…", busy = true)
                check(connection.discoverServices()) { "서비스 탐색을 시작할 수 없음" }
            }
        }

        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            if (connection !== gatt) return
            guarded {
                check(status == BluetoothGatt.GATT_SUCCESS) { "서비스 탐색 실패: $status" }
                if (!identityGate.servicesReady()) return@guarded
                val service = checkNotNull(connection.getService(SERVICE)) { "기기에 B81D 서비스가 없음" }
                send = checkNotNull(service.getCharacteristic(SEND)) { "송신 특성이 없음" }
                receiveCharacteristic = checkNotNull(service.getCharacteristic(RECEIVE)) { "수신 특성이 없음" }
                pairInfo = checkNotNull(service.getCharacteristic(PAIR_INFO)) { "신원 특성이 없음" }
                state("B81D 발견, 협상 중: BLE MTU…", busy = true)
                check(connection.requestMtu(512)) { "MTU 협상을 시작할 수 없음" }
            }
        }

        override fun onMtuChanged(connection: BluetoothGatt, mtu: Int, status: Int) {
            if (connection !== gatt || !identityGate.mtuReady()) return
            guarded {
                check(status == BluetoothGatt.GATT_SUCCESS && mtu >= 160) {
                    "BLE MTU  부족: mtu=$mtu, status=$status"
                }
                log("BLE MTU=$mtu")
                blePayloadSize = mtu - 3
                state("안경 신원 읽는 중…", busy = true)
                val characteristic = checkNotNull(pairInfo) { "안경 신원 서비스를 찾을 수 없습니다. 연결만 해제한 뒤 다시 시도하세요." }
                check(connection.readCharacteristic(characteristic)) {
                    "신원 특성을 읽을 수 없음"
                }
            }
        }

        override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
                                          value: ByteArray, status: Int) {
            if (connection !== gatt || characteristic.uuid != PAIR_INFO) return
            guarded {
                check(status == BluetoothGatt.GATT_SUCCESS && value.size >= 13) { "안경 신원 읽기 실패: $status" }
                classicAddress = value.copyOfRange(0, 6)
                val address = addressString(classicAddress)
                check(expectedAddress == null || expectedAddress.equals(address, true)) { "BLE 가 선택한 클래식 블루투스 기기와 일치하지 않음" }
                expectedAddress = address
                val bound = value.copyOfRange(7, 13)
                val hasBinding = bound.any { it != 0.toByte() }
                val systemBonded = adapter.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED
                // Keep existing binding; do not replace credentials or remove a bond.
                identity = if (hasBinding) bound else androidIdentity()
                connectionPairValue = ProtocolCodec.pairingIntentForLink(automaticConnection, systemBonded, hasBinding)
                log("신원 읽음; 상태=${value[6].toInt() and 255}; 신원 출처=${if (hasBinding) "안경 바인딩" else "이 앱의 Android ID"}; 시스템 페어링=$systemBonded; 연결 모드=$connectionPairValue")
                val receive = checkNotNull(receiveCharacteristic) { "안경 수신 서비스를 찾을 수 없음" }
                check(connection.setCharacteristicNotification(receive, true)) { "BLE 알림을 켤 수 없음" }
                val descriptor = checkNotNull(receive.getDescriptor(CCCD)) { "누락: CCCD" }
                state("BLE 알림 구독 중…", busy = true)
                check(connection.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS) {
                    "쓰기 불가: CCCD"
                }
            }
        }

        override fun onDescriptorWrite(connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (connection !== gatt || descriptor.uuid != CCCD) return
            guarded {
                check(status == BluetoothGatt.GATT_SUCCESS) { "BLE 알림 구독 실패: $status" }
                state("기기 정보 교환 중…", busy = true)
                writePairing(17, listOf(
                    16 to byteArrayOf(1), 17 to identity,
                    18 to (adapter.name ?: "OpenRayneo Android").toByteArray(),
                    19 to byteArrayOf(2), 22 to Build.MANUFACTURER.toByteArray(),
                    // Existing manual and automatic reconnections use the same
                    // documented reconnect intent; new registration is separate.
                    23 to Build.MODEL.toByteArray(), 27 to byteArrayOf(connectionPairValue)
                ))
            }
        }

        override fun onCharacteristicWrite(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (connection !== gatt || characteristic.uuid != SEND) return
            guarded {
                if (!bleWriting) return@guarded
                bleWriteTimeout?.let(main::removeCallbacks); bleWriteTimeout = null
                check(status == BluetoothGatt.GATT_SUCCESS) { "BLE 쓰기 콜백 실패: status=$status" }
                bleWriting = false
                bleBusyRetries = 0
                val completed = bleWrites.removeFirstOrNull()
                completed?.completion?.let(::log)
                completed?.callback?.invoke(Result.success(Unit))
                writeNextBleChunk()
            }
        }

        override fun onCharacteristicChanged(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (connection !== gatt || characteristic.uuid != RECEIVE) return
            guarded {
                bleDecoder.append(value).forEach { frame ->
                    if (frame.acknowledgement) {
                        if (bleOnly) log("BLE 전송 확인 수신: seq=${frame.sequence}")
                    } else {
                        if (frame.requiresAcknowledgement) {
                            enqueueBleFrame(ProtocolCodec.acknowledgement(frame), null)
                        }
                        if (frame.protocol == 0x10) processPairing(frame)
                        else if (frame.protocol == 0x06 && !ready && authenticationHandled && !bleOnly &&
                            ProtocolCodec.isCapabilityRequest(frame)) {
                            // Retain just the latest bounded request for this generation.
                            pendingCapability = frame
                            log("BLE 인증 후 기능 협상 요청 수신 · SPP 연결 후 응답")
                        } else listener.onFrame(frame, "BLE")
                    }
                }
            }
        }
    }

    private fun processPairing(frame: ProtocolCodec.Frame) {
        check(frame.payload.size >= 3) { "인증 응답이 불완전함" }
        val command = frame.payload[0].toInt() and 255
        log("BLE 명령 수신 $command")
        when (command) {
            17 -> {
                val fields = ProtocolCodec.pairingFields(frame.payload)
                check(25 !in fields && fields[26]?.isNotEmpty() != true) {
                    "안경이 계정 바인딩 인증을 요구하지만 현재 검증 버전은 지원하지 않음"
                }
                val random = ByteArray(4).also { SecureRandom().nextBytes(it) }
                state("앱 인증 진행 중…", busy = true)
                writePairing(24, listOf(16 to random, 17 to authentication(random)))
            }
            25 -> {
                val fields = ProtocolCodec.pairingFields(frame.payload)
                val random = checkNotNull(fields[16]) { "인증 응답에 챌린지 값이 없음" }
                check(random.size == 4) { "인증 챌린지 값 길이 오류" }
                check(fields[17]?.contentEquals(authentication(random)) == true) { "앱 인증이 거부됨" }
                if (!authenticationHandled) {
                    authenticationHandled = true
                    log("BLE  앱 인증 통과")
                    if (bleOnly) {
                        main.removeCallbacks(timeout)
                        ready = true
                        state("BLE  인증됨, 알림 시도 가능 (완전한 페어링은 미검증)", ready = true)
                    } else openRfcomm()
                }
            }
            22 -> log("안경 연결 상태 cmd22=${ProtocolCodec.pairingStatus(frame.payload) ?: "미확인"}")
            23 -> log("안경 등록 상태 cmd23=${ProtocolCodec.pairingStatus(frame.payload) ?: "미확인"}")
            30 -> log("안경 SPP 연결 요청 cmd30 수신")
        }
    }

    private fun writePairing(command: Int, fields: List<Pair<Int, ByteArray>>) {
        val bytes = ProtocolCodec.pairing(pairingSequence++, command, fields)
        enqueueBleFrame(bytes, "BLE 명령 전송 $command (${bytes.size}바이트)")
    }

    private fun enqueueBleFrame(bytes: ByteArray, completion: String?, callback: ((Result<Unit>) -> Unit)? = null) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + blePayloadSize, bytes.size)
            bleWrites.addLast(BleWrite(bytes.copyOfRange(offset, end), if (end == bytes.size) completion else null,
                if (end == bytes.size) callback else null))
            offset = end
        }
        writeNextBleChunk()
    }

    private fun writeNextBleChunk() {
        if (bleWriting || bleRetry != null || bleWrites.isEmpty()) return
        val connection = checkNotNull(gatt)
        val session = generation
        val result = connection.writeCharacteristic(checkNotNull(send), bleWrites.first().bytes,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        if (result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && bleBusyRetries < busyDelays.size) {
            val delay = busyDelays[bleBusyRetries++]
            log("BLE 전송 일시 혼잡 · 재시도 $bleBusyRetries/${busyDelays.size}")
            val task = Runnable {
                bleRetry = null
                if (generation == session && gatt === connection) guarded { writeNextBleChunk() }
            }
            bleRetry = task
            main.postDelayed(task, delay)
            return
        }
        check(result == BluetoothStatusCodes.SUCCESS) { "BLE 전송 요청 실패: code=$result" }
        bleWriting = true
        // The submitted chunk is never replayed if its result is ambiguous.
        val deadline = Runnable {
            if (generation == session && gatt === connection && bleWriting) fail("BLE 쓰기 응답 시간 초과")
        }
        bleWriteTimeout = deadline
        main.postDelayed(deadline, 5_000)
    }

    private fun authentication(random: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(random + identity + classicAddress + AUTH_CONSTANT)

    private fun androidIdentity(): ByteArray {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        check(!id.isNullOrEmpty() && id.length >= 12) { "이 앱의 Android ID를 사용할 수 없음" }
        return id.takeLast(12).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun openRfcomm() {
        val target = adapter.getRemoteDevice(checkNotNull(expectedAddress))
        ensureBond(target) { connectRfcomm(target) }
    }

    private fun connectRfcomm(target: BluetoothDevice) {
        state("BLE  인증됨, 연결 중: SPP…", busy = true)
        val session = generation
        log("클래식 블루투스 페어링 상태=${target.bondState}; RFCOMM 서비스 UUID=$SPP")
        val pending = target.createRfcommSocketToServiceRecord(SPP)
        socket = pending
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 20_000)
        io.execute {
            try {
                adapter.cancelDiscovery()
                pending.connect()
                main.post {
                    if (session != generation || socket !== pending) return@post
                    main.removeCallbacks(timeout)
                    ready = true
                    startReader(pending, session)
                    state("연결됨: BLE 인증과 RFCOMM 모두 정상", ready = true)
                    val capability = pendingCapability
                    pendingCapability = null
                    if (session == generation && capability != null) listener.onFrame(capability, "BLE")
                }
            } catch (error: Exception) {
                main.post { if (session == generation) fail("SPP  연결 실패: ${error.message}") }
            }
        }
    }

    private fun startReader(connection: BluetoothSocket, session: Int) {
        Thread({
            val decoder = ProtocolCodec.StreamDecoder()
            val buffer = ByteArray(4096)
            try {
                while (socket === connection) {
                    val count = connection.inputStream.read(buffer)
                    check(count >= 0) { "안경이 데이터 스트림을 닫음" }
                    val frames = decoder.append(buffer.copyOf(count))
                    // Match upstream transport semantics: reply on the same SPP stream
                    // to frames requesting receipts. ACK frames themselves never get ACKed.
                    val acks = ProtocolCodec.transportAcknowledgements(frames)
                    if (acks.isNotEmpty()) io.execute {
                        if (session != generation || socket !== connection) return@execute
                        try {
                            acks.forEach { connection.outputStream.write(it) }
                            connection.outputStream.flush()
                        } catch (error: Exception) {
                            main.post {
                                if (session == generation && socket === connection)
                                    fail("SPP 확인 응답 전송 실패: ${error.message}")
                            }
                        }
                    }
                    main.post {
                        if (session == generation) frames.forEach {
                            if (!it.acknowledgement) listener.onFrame(it, "SPP")
                        }
                    }
                }
            } catch (error: Exception) {
                main.post { if (session == generation) fail("데이터 연결 종료: ${error.message}") }
            }
        }, "rayneo-reader").start()
    }

    /**
     * Diagnostic: three notifications 2 s apart — English only, the original Chinese text, and Korean —
     * to tell whether the glasses firmware drops messages containing glyphs its font lacks.
     */
    fun sendTestNotification() {
        sendNotification("Test 1 (EN)", "Hello RayNeo glasses. English only.")
        main.postDelayed({ if (ready) sendNotification("测试 2 (中文)", "安卓直连验证：你好，雷鸟眼镜。") }, 2_000)
        main.postDelayed({ if (ready) sendNotification("Test 3 (KO)", "한국어 테스트: 안녕하세요, RayNeo 안경.") }, 4_000)
    }

    fun sendNotification(title: String, body: String) {
        check(ready) { "먼저 안경을 연결하세요" }
        val timestamp = System.currentTimeMillis().toString()
        val payload = JSONObject().apply {
            put("notificationUID", timestamp)
            put("appId", "com.openrayneo.bridge")
            put("appName", "OpenRayneo Android")
            put("title", title)
            put("subtitle", JSONObject.NULL)
            put("content", body)
            put("timestamp", timestamp)
            put("category", 0)
            put("reply", false)
            put("type", 1)
        }
        val frame = ProtocolCodec.notification(notificationSequence++, payload.toString())
        if (bleOnly) {
            enqueueBleFrame(frame, "BLE 알림을 전송했습니다. 안경에서 표시를 확인하세요")
            return
        }
        sendFrame(frame) { result ->
            result.onSuccess { log("알림을 전송했습니다. 안경에서 표시를 확인하세요") }.onFailure { log("알림 전송 실패: ${it.message}") }
        }
    }

    /** Explicit control-path test; do not move large audio or other business writes
     * to BLE implicitly. GATT write callback is not a device business response. */
    fun sendBleControl(frame: ByteArray, completion: (Result<Unit>) -> Unit) {
        check(ready && authenticationHandled && !bleOnly && gatt != null) { "완전한 연결의 인증된 BLE가 필요합니다" }
        check(ProtocolCodec.isBleLauncherControl(frame, blePayloadSize)) { "BLE 진단 허용 명령 또는 단일 프레임 크기를 벗어났습니다" }
        check(bleWrites.size < 32) { "BLE 전송 대기열이 혼잡합니다" }
        try {
            enqueueBleFrame(frame, "BLE 음성 제어 전송 완료 · 기기 회신 별도", completion)
        } catch (error: Exception) {
            fail("BLE 제어 전송 실패: ${error.message}")
        }
    }

    fun sendFrame(frame: ByteArray, completion: (Result<Unit>) -> Unit) {
        check(ready && !bleOnly) { "이 기능은 완전한 RFCOMM 연결이 필요합니다. BLE-only 모드를 끄세요" }
        val session = generation
        val connection = checkNotNull(socket)
        io.execute {
            try {
                check(socket === connection) { "연결이 변경됨" }
                connection.outputStream.write(frame)
                connection.outputStream.flush()
                main.post { completion(if (session == generation) Result.success(Unit) else Result.failure(IllegalStateException("연결이 변경됨"))) }
            } catch (error: Exception) {
                main.post {
                    completion(Result.failure(error))
                    if (session == generation) fail("데이터 전송 실패: ${error.message}")
                }
            }
        }
    }

    private fun stopScan() {
        scanTimeout?.let(main::removeCallbacks)
        scanTimeout = null
        val callback = activeScan ?: return
        activeScan = null; presenceScan = null
        // Invalidate the callback even if Bluetooth was turned off or permission revoked.
        runCatching { adapter.bluetoothLeScanner?.stopScan(callback) }
            .onFailure { log("BLE 스캔 정리 실패: ${it.message ?: it.javaClass.simpleName}") }
    }

    fun disconnect() { resetConnection(); state("연결 끊김") }

    private fun resetConnection() {
        generation++
        main.removeCallbacks(timeout)
        stopObservingBond()
        stopScan()
        ready = false
        authenticationHandled = false
        connectionPairValue = 1
        pendingCapability = null
        bleRetry?.let(main::removeCallbacks); bleRetry = null
        bleWriteTimeout?.let(main::removeCallbacks); bleWriteTimeout = null
        bleBusyRetries = 0
        val cancelled = bleWrites.mapNotNull { it.callback }
        bleWrites.clear()
        // Complete cancelled operations after teardown, not reentrantly in reset.
        cancelled.forEach { cb -> main.post { cb(Result.failure(IllegalStateException("BLE 연결이 변경됨"))) } }
        bleWriting = false
        blePayloadSize = 20
        val oldGatt = gatt
        gatt = null
        runCatching { oldGatt?.disconnect() }
        runCatching { oldGatt?.close() }
        val oldSocket = socket
        socket = null
        runCatching { oldSocket?.close() }
        send = null
        pairInfo = null
        receiveCharacteristic = null
        identityGate.reset()
    }

    fun close() { disconnect(); io.shutdownNow() }

    // Publish the actual failure once, atomically with teardown, so the supervisor can retain it.
    fun abortConnection(message: String) = fail(message)
    private fun fail(message: String) { resetConnection(); state(message) }
    private fun guarded(action: () -> Unit) {
        try { action() } catch (error: Exception) { fail(error.message ?: error.javaClass.simpleName) }
    }
    private fun state(text: String, ready: Boolean = false, busy: Boolean = false) {
        if (busy) connectionStage = text
        listener.onState(text, ready, busy)
        log(text)
    }
    private fun log(text: String) {
        Log.i("OpenRayneo", text)
        listener.onLog(text)
    }

    companion object {
        private val SERVICE = UUID.fromString("0000B81D-0000-1000-8000-00805F9B34FB")
        private val SEND = UUID.fromString("EA8B70D5-2BD3-49AB-9C31-9C38B2C3C4F9")
        private val RECEIVE = UUID.fromString("7DB3E235-3608-41F3-A03C-955FCBD2EA4B")
        private val PAIR_INFO = UUID.fromString("EA8B60C5-2BD3-49AB-9C31-9D38B1C5C5F9")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        // RayNeo business service (APK V3/p); standard SPP is used by RNLink log capture.
        private val SPP = UUID.fromString("99999999-9999-9999-9999-999999999999")
        private val AUTH_CONSTANT = intArrayOf(7, 18, 200, 173, 39, 116, 65, 149, 222, 95, 161, 234, 109, 2, 95, 184)
            .map { it.toByte() }.toByteArray()
        private fun addressString(bytes: ByteArray) = bytes.joinToString(":") { "%02X".format(it.toInt() and 255) }
    }
}
