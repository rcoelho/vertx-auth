package io.vertx.ext.jwt;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.jwt.impl.SignatureHelper;

import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.*;
import java.util.*;

/**
 * JWK https://tools.ietf.org/html/rfc7517
 *
 * In a nutshell a JWK is a Key(Pair) encoded as JSON. This implementation follows the spec with some limitations:
 *
 * * Supported algorithms are: "RS256", "RS384", "RS512", "ES256", "ES384", "ES512", "HS256", "HS384", "HS512"
 *
 * The rationale for this choice is to support the required algorithms for JWT.
 *
 * The constructor takes a single JWK (the the KeySet) or a PEM encoded pair (used by Google and useful for importing
 * standard PEM files from OpenSSL).
 *
 * * Certificate chains (x5c) only allow a single element chain, certificate urls and fingerprints are not considered.
 *
 * @author Paulo Lopes
 */
public final class JWK implements Crypto {

  private static final Charset UTF8 = StandardCharsets.UTF_8;

  // JSON JWK properties
  private final String kid;
  private String alg;

  // decoded
  private PrivateKey privateKey;
  private PublicKey publicKey;
  private Signature signature;
  private Cipher cipher;
  private X509Certificate certificate;
  private Mac mac;

  // verify/sign mode
  private boolean symmetric;
  // special handling for ECDSA
  private boolean ecdsa;
  private int ecdsaLength;

  /**
   * Creates a Symmetric Key (Hash) from pem formatted strings.
   *
   * @param algorithm the algorithm e.g.: HS256
   * @param secret the private key
   */
  public static JWK symmetricKey(String algorithm, String secret) {
    return new JWK(algorithm, secret);
  }

  /**
   * Creates a Public Key from a PEM formatted string.
   *
   * @param algorithm the algorithm e.g.: RS256
   * @param pemString the public key in PEM format
   */
  public static JWK pubKey(String algorithm, String pemString) {
    return new JWK(algorithm, false, pemString, null);
  }

  /**
   * Creates a Private Key from a PEM formatted string.
   *
   * @param algorithm the algorithm e.g.: RS256
   * @param pemString the private key in PEM format
   */
  public static JWK secKey(String algorithm, String pemString) {
    return new JWK(algorithm, false, null, pemString);
  }

  /**
   * Creates a Key pair from a PEM formatted string.
   *
   * @param algorithm the algorithm e.g.: RS256
   * @param pubPemString the public key in PEM format
   * @param secPemString the private key in PEM format
   */
  public static JWK pubSecKey(String algorithm, String pubPemString, String secPemString) {
    return new JWK(algorithm, false, pubPemString, secPemString);
  }

  /**
   * Creates a Certificate from a PEM formatted string.
   *
   * @param algorithm the algorithm e.g.: RS256
   * @param pemString the X509 certificate in PEM format
   */
  public static JWK certificate(String algorithm, String pemString) {
    return new JWK(algorithm, true, pemString, null);
  }

  public static JWK from(PubSecKeyOptions options) {
    String alg = options.getAlgorithm();
    if (alg.startsWith("HS")) {
      // HMAC SHA
      return symmetricKey(alg, options.getSecretKey());
    }

    String pub = options.getPublicKey();
    String sec = options.getSecretKey();

    // Pub Sec key
    if (pub != null && sec != null) {
      return pubSecKey(alg, pub, sec);
    }

    if (pub != null) {
      if (options.isCertificate()) {
        return certificate(alg, pub);
      } else {
        return pubKey(alg, pub);
      }
    }

    if (sec != null) {
      return secKey(alg, sec);
    }

    throw new IllegalArgumentException("Missing PUB/SEC keys");
  }

  /**
   * Creates a Key(Pair) from pem formatted strings.
   *
   * @param algorithm the algorithm e.g.: RS256
   * @param isCertificate when true the public PEM is assumed to be a X509 Certificate
   * @param pemPub the public key in PEM format
   * @param pemSec the private key in PEM format
   */
  private JWK(String algorithm, boolean isCertificate, String pemPub, String pemSec) {

    try {
      final Map<String, String> alias = new HashMap<String, String>() {{
        put("RS256", "SHA256withRSA");
        put("RS384", "SHA384withRSA");
        put("RS512", "SHA512withRSA");
        put("ES256", "SHA256withECDSA");
        put("ES384", "SHA384withECDSA");
        put("ES512", "SHA512withECDSA");
        put("PS256", "RSASSA-PSS");
        put("PS384", "RSASSA-PSS");
        put("PS512", "RSASSA-PSS");
      }};

      final KeyFactory kf;

      switch (algorithm) {
        case "RS256":
        case "RS384":
        case "RS512":
        case "PS256":
        case "PS384":
        case "PS512":
          kf = KeyFactory.getInstance("RSA");
          break;
        case "ES256":
        case "ES384":
        case "ES512":
          kf = KeyFactory.getInstance("EC");
          ecdsa = true;
          ecdsaLength = ECDSALength(alias.get(algorithm));
          break;
        default:
          throw new RuntimeException("Unknown algorithm factory for: " + algorithm);
      }

      alg = algorithm;
      kid = algorithm + (pemPub !=  null ? pemPub.hashCode() : "") + "-" + (pemSec !=  null ? pemSec.hashCode() : "");

      if (pemPub != null) {
        if (isCertificate) {
          final CertificateFactory cf = CertificateFactory.getInstance("X.509");
          certificate = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(pemPub.getBytes(UTF8)));
        } else {
          final X509EncodedKeySpec keyspec = new X509EncodedKeySpec(Base64.getMimeDecoder().decode(pemPub));
          publicKey = kf.generatePublic(keyspec);
        }
      }

      if (pemSec != null) {
        final PKCS8EncodedKeySpec keyspec = new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(pemSec));
        privateKey = kf.generatePrivate(keyspec);
      }

      // use default
      signature = Signature.getInstance(alias.get(alg));

      // signature extras
      switch (alg) {
        case "PS256":
          signature.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 256 / 8, 1));
          break;
        case "PS384":
          signature.setParameter(new PSSParameterSpec("SHA-384", "MGF1", MGF1ParameterSpec.SHA384, 384 / 8, 1));
          break;
        case "PS512":
          signature.setParameter(new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 512 / 8, 1));
          break;
      }
    } catch (InvalidKeySpecException | CertificateException | NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
      // error
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates a Symmetric Key from a base64 encoded string.
   *
   * @param algorithm the algorithm e.g.: HS256
   * @param hmac the symmetric key
   */
  private JWK(String algorithm, String hmac) {
    try {
      final Map<String, String> alias = new HashMap<String, String>() {{
        put("HS256", "HMacSHA256");
        put("HS384", "HMacSHA384");
        put("HS512", "HMacSHA512");
      }};

      alg = algorithm;

      // abort if the specified algorithm is not known
      if (!alias.containsKey(alg)) {
        throw new NoSuchAlgorithmException(alg);
      }

      kid = algorithm + hmac.hashCode();

      mac = Mac.getInstance(alias.get(alg));
      mac.init(new SecretKeySpec(hmac.getBytes(UTF8), alias.get(alg)));
      // this is a symmetric key
      symmetric = true;
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new RuntimeException(e);
    }
  }

  public JWK(JsonObject json) {
    kid = json.getString("kid", UUID.randomUUID().toString());

    try {
      switch (json.getString("kty")) {
        case "RSA":
        case "RSASSA":
          createRSA(json);
          break;
        case "EC":
          createEC(json);
          break;
        case "oct":
          createOCT(json);
          break;

        default:
          throw new RuntimeException("Unsupported key type: " + json.getString("kty"));
      }
    } catch (NoSuchAlgorithmException | InvalidKeyException | InvalidKeySpecException | InvalidParameterSpecException | CertificateException | NoSuchPaddingException e) {
      throw new RuntimeException(e);
    }
  }

  private void createRSA(JsonObject json) throws NoSuchAlgorithmException, InvalidKeySpecException, CertificateException, NoSuchPaddingException {
    final Map<String, String> alias = new HashMap<String, String>() {{
      put("RS256", "SHA256withRSA");
      put("RS384", "SHA384withRSA");
      put("RS512", "SHA512withRSA");
      put("PS256", "RSASSA-PSS");
      put("PS384", "RSASSA-PSS");
      put("PS512", "RSASSA-PSS");
      // COSE required
      put("RS1", "SHA1withRSA");
    }};

    // get the alias for the algorithm
    alg = json.getString("alg", "RS256");

    // abort if the specified algorithm is not known
    if (!alias.containsKey(alg)) {
      throw new NoSuchAlgorithmException(alg);
    }

    // public key
    if (jsonHasProperties(json, "n", "e")) {
      final BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("n")));
      final BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("e")));
      publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
    }

    // private key
    if (jsonHasProperties(json, "n", "e", "d", "p", "q", "dp", "dq", "qi")) {
      final BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("n")));
      final BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("e")));
      final BigInteger d = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("d")));
      final BigInteger p = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("p")));
      final BigInteger q = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("q")));
      final BigInteger dp = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("dp")));
      final BigInteger dq = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("dq")));
      final BigInteger qi = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("qi")));

      privateKey = KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateCrtKeySpec(n, e, d, p, q, dp, dq, qi));
    }

    // certificate chain
    if (json.containsKey("x5c")) {
      JsonArray x5c = json.getJsonArray("x5c");

      if (x5c.size() > 1) {
        // TODO: handle more than 1 value
        throw new RuntimeException("Certificate Chain length > 1 is not supported");
      }

      CertificateFactory cf = CertificateFactory.getInstance("X.509");

      certificate = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(addBoundaries(x5c.getString(0)).getBytes(UTF8)));
    }

    switch (json.getString("use", "sig")) {
      case "sig":
        try {
          // use default
          signature = Signature.getInstance(alias.get(alg));
          // signature extras
          switch (alg) {
            case "PS256":
              signature.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 256 / 8, 1));
              break;
            case "PS384":
              signature.setParameter(new PSSParameterSpec("SHA-384", "MGF1", MGF1ParameterSpec.SHA384, 384 / 8, 1));
              break;
            case "PS512":
              signature.setParameter(new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 512 / 8, 1));
              break;
          }
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
          // error
          throw new RuntimeException(e);
        }
        break;
      case "enc":
        cipher = Cipher.getInstance("RSA");
    }
  }

  private String addBoundaries(final String certificate){
    return "-----BEGIN CERTIFICATE-----\n" + certificate + "\n-----END CERTIFICATE-----";
  }

  private void createEC(JsonObject json) throws NoSuchAlgorithmException, InvalidKeySpecException, InvalidParameterSpecException, NoSuchPaddingException {
    final Map<String, String> alias = new HashMap<String, String>() {{
      put("ES256", "SHA256withECDSA");
      put("ES384", "SHA384withECDSA");
      put("ES512", "SHA512withECDSA");
    }};

    // get the alias for the algorithm
    alg = json.getString("alg", "ES256");
    // are the signatures expected to be in ASN.1/DER format?
    // JWK spec states yes, however COSE not really
    ecdsa = json.getBoolean("asn1", true);

    // abort if the specified algorithm is not known
    if (!alias.containsKey(alg)) {
      throw new NoSuchAlgorithmException(alg);
    }

    ecdsaLength = ECDSALength(alias.get(alg));

    AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
    parameters.init(new ECGenParameterSpec(translate(json.getString("crv"))));

    // public key
    if (jsonHasProperties(json, "x", "y")) {
      final BigInteger x = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("x")));
      final BigInteger y = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("y")));
      publicKey = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), parameters.getParameterSpec(ECParameterSpec.class)));
    }

    // public key
    if (jsonHasProperties(json, "x", "y", "d")) {
      final BigInteger x = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("x")));
      final BigInteger y = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("y")));
      final BigInteger d = new BigInteger(1, Base64.getUrlDecoder().decode(json.getString("d")));
      privateKey = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(d, parameters.getParameterSpec(ECParameterSpec.class)));
    }

    switch (json.getString("use", "sig")) {
      case "sig":
        try {
          // use default
          signature = Signature.getInstance(alias.get(alg));
        } catch (NoSuchAlgorithmException e) {
          // error
          throw new RuntimeException(e);
        }
        break;
      case "enc":
      default:
        throw new RuntimeException("EC Encryption not supported");
    }
  }

  private void createOCT(JsonObject json) throws NoSuchAlgorithmException, InvalidKeyException, InvalidKeySpecException {
    final Map<String, String> alias = new HashMap<String, String>() {{
      put("HS256", "HMacSHA256");
      put("HS384", "HMacSHA384");
      put("HS512", "HMacSHA512");
    }};

    // get the alias for the algorithm
    alg = json.getString("alg", "HS256");

    // abort if the specified algorithm is not known
    if (!alias.containsKey(alg)) {
      throw new NoSuchAlgorithmException(alg);
    }

    mac = Mac.getInstance(alias.get(alg));
    mac.init(new SecretKeySpec(json.getString("k").getBytes(UTF8), alias.get(alg)));
    // this is a symmetric key
    symmetric = true;
  }

  public String getAlgorithm() {
    return alg;
  }

  @Override
  public String getId() {
    return kid;
  }

  public Key unwrap() {
    if (privateKey != null) {
      return privateKey;
    }
    if (publicKey != null) {
      return publicKey;
    }
    if (certificate != null) {
      return certificate.getPublicKey();
    }
    return null;
  }

  public synchronized byte[] encrypt(byte[] payload) {
    if (cipher == null) {
      throw new RuntimeException("Key use is not 'enc'");
    }

    try {
      cipher.init(Cipher.ENCRYPT_MODE, publicKey);
      cipher.update(payload);
      return cipher.doFinal();
    } catch (InvalidKeyException | BadPaddingException | IllegalBlockSizeException e) {
      throw new RuntimeException(e);
    }
  }

  public synchronized byte[] decrypt(byte[] payload) {
    if (cipher == null) {
      throw new RuntimeException("Key use is not 'enc'");
    }

    try {
      cipher.init(Cipher.DECRYPT_MODE, privateKey);
      cipher.update(payload);
      return cipher.doFinal();
    } catch (InvalidKeyException | BadPaddingException | IllegalBlockSizeException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public synchronized byte[] sign(byte[] payload) {
    if (symmetric) {
      return mac.doFinal(payload);
    } else {
      if (signature == null) {
        throw new RuntimeException("Key use is not 'sig'");
      }

      try {
        signature.initSign(privateKey);
        signature.update(payload);
        if (ecdsa) {
          return SignatureHelper.toJWS(signature.sign(), ecdsaLength);
        } else {
          return signature.sign();
        }
      } catch (SignatureException | InvalidKeyException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public synchronized boolean verify(byte[] expected, byte[] payload) {
    if (symmetric) {
      return Arrays.equals(expected, sign(payload));
    } else {
      if (signature == null) {
        throw new RuntimeException("Key use is not 'sig'");
      }

      try {
        if (publicKey != null) {
          signature.initVerify(publicKey);
        }
        if (certificate != null) {
          signature.initVerify(certificate);
        }
        signature.update(payload);
        if (ecdsa) {
          return signature.verify(SignatureHelper.toDER(expected));
        } else {
          return signature.verify(expected);
        }
      } catch (SignatureException | InvalidKeyException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static String translate(String crv) {
    switch (crv) {
      case "P-256":
        return "secp256r1";
      case "P-384":
        return "secp384r1";
      case "P-521":
        return "secp521r1";
      default:
        return "";
    }
  }

  private static boolean jsonHasProperties(JsonObject json, String... properties) {
    for (String property : properties) {
      if (!json.containsKey(property) || json.getValue(property) == null) {
        return false;
      }
    }

    return true;
  }
}
