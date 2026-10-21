package dev.youbal.callrec.lib;

import android.content.Context;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

/**
 * The identity this phone presents to its own wireless debugging. Created once, kept in the app's private storage,
 * and trusted by Android after the pairing code has been entered.
 */
final class AdbKeys {

    final PrivateKey privateKey;
    final Certificate certificate;

    private AdbKeys(PrivateKey privateKey, Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    static AdbKeys load(Context context) throws Exception {
        File keyFile = new File(context.getFilesDir(), "adb_key.der");
        File certFile = new File(context.getFilesDir(), "adb_cert.der");
        if (keyFile.isFile() && certFile.isFile()) {
            try {
                PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
                Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
                return new AdbKeys(key, cert);
            } catch (Exception damaged) {
                // Fall through and make a new identity; the user will have to pair again.
            }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Certificate cert = selfSigned(pair);
        write(keyFile, pair.getPrivate().getEncoded());
        write(certFile, cert.getEncoded());
        return new AdbKeys(pair.getPrivate(), cert);
    }

    /** Forgets the identity. The phone will ask for a new pairing code next time. */
    static void forget(Context context) {
        new File(context.getFilesDir(), "adb_key.der").delete();
        new File(context.getFilesDir(), "adb_cert.der").delete();
    }

    private static void write(File file, byte[] bytes) throws IOException {
        Files.write(file.toPath(), bytes);
        file.setReadable(false, false);
        file.setReadable(true, true);
    }

    /** A self-signed certificate, which is all wireless debugging asks of a client. */
    private static Certificate selfSigned(KeyPair pair) throws Exception {
        long now = System.currentTimeMillis();
        X500Name name = new X500Name("CN=callrec");
        AlgorithmIdentifier algorithm = new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE);

        V3TBSCertificateGenerator generator = new V3TBSCertificateGenerator();
        generator.setSerialNumber(new ASN1Integer(BigInteger.valueOf(now)));
        generator.setIssuer(name);
        generator.setSubject(name);
        generator.setStartDate(new Time(new Date(now - 24L * 3600 * 1000)));
        generator.setEndDate(new Time(new Date(now + 3650L * 24 * 3600 * 1000)));
        generator.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()));
        generator.setSignature(algorithm);
        TBSCertificate tbs = generator.generateTBSCertificate();

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs.getEncoded(ASN1Encoding.DER));

        ASN1EncodableVector parts = new ASN1EncodableVector();
        parts.add(tbs);
        parts.add(algorithm);
        parts.add(new DERBitString(signer.sign()));
        byte[] der = new DERSequence(parts).getEncoded(ASN1Encoding.DER);
        return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }
}
