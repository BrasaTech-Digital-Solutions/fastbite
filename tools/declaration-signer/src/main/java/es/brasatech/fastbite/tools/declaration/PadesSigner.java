package es.brasatech.fastbite.tools.declaration;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.IssuerSerial;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSAttributeTableGenerator;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Signs a PDF with a PKCS#12 certificate as PAdES baseline B-B (ETSI EN 319 142-1): a detached CAdES signature with
 * the signing-certificate-v2 attribute and no signing-time attribute (the time goes in the signature dictionary).
 */
public final class PadesSigner {

    /** Who signed and whether the signature covers the whole document and matches it. */
    public record Verification(String signer, boolean valid, boolean coversWholeFile, boolean hasSigningCertificateV2,
            Calendar signedAt) {
        public boolean ok() { return valid && coversWholeFile && hasSigningCertificateV2; }
    }

    private final PrivateKey key;
    private final List<X509Certificate> chain;

    private PadesSigner(PrivateKey key, List<X509Certificate> chain) {
        this.key = key;
        this.chain = chain;
    }

    /** The first private key in the PKCS#12 file, with its certificate chain. The password is never logged. */
    public static PadesSigner fromPkcs12(Path file, char[] password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, password);
        }
        for (String alias : Collections.list(store.aliases())) {
            if (store.isKeyEntry(alias)) {
                List<X509Certificate> chain = new ArrayList<>();
                for (Certificate certificate : store.getCertificateChain(alias)) {
                    chain.add((X509Certificate) certificate);
                }
                return new PadesSigner((PrivateKey) store.getKey(alias, password), chain);
            }
        }
        throw new GeneralSecurityException("The certificate file has no private key");
    }

    public X509Certificate certificate() {
        return chain.getFirst();
    }

    /** The certificate holder's name (CN), as signature panels show it. */
    public String signerName() {
        return commonName(X500Name.getInstance(certificate().getSubjectX500Principal().getEncoded()));
    }

    public void sign(Path in, Path out, String reason, String location) throws IOException {
        certificateMustBeValidNow();
        try (PDDocument document = Loader.loadPDF(in.toFile())) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
            signature.setName(signerName());
            signature.setReason(reason);
            signature.setLocation(location);
            signature.setSignDate(Calendar.getInstance());
            SignatureOptions options = new SignatureOptions();
            options.setPreferredSignatureSize(SignatureOptions.DEFAULT_SIGNATURE_SIZE * 2);
            document.addSignature(signature, content -> {
                try {
                    return cms(content.readAllBytes());
                } catch (GeneralSecurityException | org.bouncycastle.cms.CMSException
                         | org.bouncycastle.operator.OperatorCreationException e) {
                    throw new IOException("Could not sign: " + e.getMessage(), e);
                }
            }, options);
            try (OutputStream output = Files.newOutputStream(out)) {
                document.saveIncremental(output);
            }
            options.close();
        }
    }

    private byte[] cms(byte[] content) throws GeneralSecurityException, org.bouncycastle.cms.CMSException,
            org.bouncycastle.operator.OperatorCreationException, IOException {
        X509Certificate certificate = certificate();
        byte[] certificateHash = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        IssuerSerial issuerSerial = new IssuerSerial(
                new GeneralNames(new GeneralName(X500Name.getInstance(certificate.getIssuerX500Principal().getEncoded()))),
                certificate.getSerialNumber());
        ESSCertIDv2 certificateId = new ESSCertIDv2(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256),
                certificateHash, issuerSerial);
        ASN1EncodableVector attributes = new ASN1EncodableVector();
        attributes.add(new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                new DERSet(new SigningCertificateV2(new ESSCertIDv2[]{certificateId}))));
        DefaultSignedAttributeTableGenerator defaults = new DefaultSignedAttributeTableGenerator(new AttributeTable(attributes));
        // PAdES: the signing time lives in the signature dictionary, never in the CMS
        CMSAttributeTableGenerator withoutSigningTime = parameters -> defaults.getAttributes(parameters).remove(CMSAttributes.signingTime);

        String algorithm = "EC".equals(key.getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
        CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
        generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                .setSignedAttributeGenerator(withoutSigningTime)
                .build(new JcaContentSignerBuilder(algorithm).build(key), certificate));
        generator.addCertificates(new JcaCertStore(chain));
        return generator.generate(new CMSProcessableByteArray(content), false).getEncoded();
    }

    private void certificateMustBeValidNow() throws IOException {
        try {
            certificate().checkValidity();
        } catch (GeneralSecurityException e) {
            throw new IOException("The certificate is not valid today: " + e.getMessage(), e);
        }
    }

    /** Checks every signature in the file: it matches the bytes it covers, and covers the whole file. */
    public static List<Verification> verify(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        List<Verification> results = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            for (PDSignature signature : document.getSignatureDictionaries()) {
                int[] range = signature.getByteRange();
                boolean whole = range.length == 4 && range[0] == 0 && range[2] + range[3] == bytes.length;
                try {
                    CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(signature.getSignedContent(bytes)),
                            signature.getContents());
                    SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
                    @SuppressWarnings("unchecked")
                    X509CertificateHolder holder = (X509CertificateHolder) cms.getCertificates().getMatches(signer.getSID())
                            .iterator().next();
                    boolean valid = signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(holder));
                    boolean signingCertificate = signer.getSignedAttributes() != null
                            && signer.getSignedAttributes().get(PKCSObjectIdentifiers.id_aa_signingCertificateV2) != null;
                    results.add(new Verification(commonName(holder.getSubject()), valid, whole, signingCertificate,
                            signature.getSignDate()));
                } catch (Exception e) {
                    results.add(new Verification("?", false, whole, false, signature.getSignDate()));
                }
            }
        }
        return results;
    }

    static String sha256(Path file) throws IOException, GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String commonName(X500Name name) {
        var names = name.getRDNs(BCStyle.CN);
        return names.length == 0 ? name.toString() : IETFUtils.valueToString(names[0].getFirst().getValue());
    }
}
