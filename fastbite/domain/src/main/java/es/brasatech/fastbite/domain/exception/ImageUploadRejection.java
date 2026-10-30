package es.brasatech.fastbite.domain.exception;

/** An image upload refused because its content or size is invalid. */
public class ImageUploadRejection extends LocalizedRejection {
    public ImageUploadRejection(String key, String english, Object... args) {
        super(key, english, args);
    }
}
