package com.hibuka.soda.foundation.io;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Base page result class, extending Result, providing page information for paging responses.
 *
 * @author kangzeng.ckz
 * @since 2025/7/3
 **/
@Data
@EqualsAndHashCode(callSuper = false)
public class PageResult<T> extends Result<T> {
    private static final long serialVersionUID = 1L;

    /**
     * Total number of records.
     */
    private Long total;

    /**
     * Page number, starting from 1.
     */
    private Integer pageNo;

    /**
     * Page size.
     */
    private Integer pageSize;

    /**
     * Number of pages.
     */
    private Long pages;

    /**
     * Whether there is a next page.
     */
    private Boolean hasNext;

    /**
     * Whether there is a previous page.
     */
    private Boolean hasPrevious;

    /**
     * Current page start record index.
     */
    private Long startIndex;

    /**
     * Current page end record index.
     */
    private Long endIndex;

    /**
     * Default constructor.
     */
    public PageResult() {
    }

    /**
     * Constructor with data, total, pageNo, pageSize, and requestId.
     *
     * @param data      list of records
     * @param total     total number of records
     * @param pageNo    page number
     * @param pageSize  page size
     * @param requestId request ID
     */
    public PageResult(T data, Long total, Integer pageNo, Integer pageSize, String requestId) {
        super(data);
        setRequestId(requestId);
        this.total = total;
        this.pageNo = pageNo;
        this.pageSize = pageSize;
        this.pages = calculatePages(total, pageSize);
        this.hasNext = calculateHasNext(pageNo, pages);
        this.hasPrevious = calculateHasPrevious(pageNo);
        this.startIndex = calculateStartIndex(pageNo, pageSize);
        this.endIndex = calculateEndIndex(startIndex, pageSize, total);
    }

    /**
     * Constructor with data, total, pageNo, and pageSize.
     *
     * @param data     list of records
     * @param total    total number of records
     * @param pageNo   page number
     * @param pageSize page size
     */
    public PageResult(T data, Long total, Integer pageNo, Integer pageSize) {
        this(data, total, pageNo, pageSize, null);
    }

    /**
     * Calculates the number of pages.
     *
     * @param total    total number of records
     * @param pageSize page size
     * @return number of pages
     */
    private Long calculatePages(Long total, Integer pageSize) {
        if (total == null || total <= 0) {
            return 0L;
        }
        return total % pageSize == 0 ? total / pageSize : total / pageSize + 1;
    }

    /**
     * Calculates whether there is a next page.
     *
     * @param pageNo page number
     * @param pages  number of pages
     * @return true if there is a next page, false otherwise
     */
    private Boolean calculateHasNext(Integer pageNo, Long pages) {
        return pageNo < pages;
    }

    /**
     * Calculates whether there is a previous page.
     *
     * @param pageNo page number
     * @return true if there is a previous page, false otherwise
     */
    private Boolean calculateHasPrevious(Integer pageNo) {
        return pageNo > 1;
    }

    /**
     * Calculates the start index of the current page.
     *
     * @param pageNo   page number
     * @param pageSize page size
     * @return start index
     */
    private Long calculateStartIndex(Integer pageNo, Integer pageSize) {
        return (long) (pageNo - 1) * pageSize + 1;
    }

    /**
     * Calculates the end index of the current page.
     *
     * @param startIndex start index
     * @param pageSize   page size
     * @param total      total number of records
     * @return end index
     */
    private Long calculateEndIndex(Long startIndex, Integer pageSize, Long total) {
        Long endIndex = startIndex + pageSize - 1;
        return Math.min(endIndex, total);
    }

    /**
     * Returns a successful page result with data, total, pageNo, and pageSize.
     *
     * @param data     list of records
     * @param total    total number of records
     * @param pageNo   page number
     * @param pageSize page size
     * @param <T>      data type
     * @return successful page result
     */
    public static <T> PageResult<T> success(T data, Long total, Integer pageNo, Integer pageSize) {
        return new PageResult<>(data, total, pageNo, pageSize);
    }

    /**
     * Returns a successful page result with data, total, pageNo, pageSize, code, and message.
     *
     * @param data     list of records
     * @param total    total number of records
     * @param pageNo   page number
     * @param pageSize page size
     * @param code     response code
     * @param message  response message
     * @param <T>      data type
     * @return successful page result
     */
    public static <T> PageResult<T> success(T data, Long total, Integer pageNo, Integer pageSize, int code, String message) {
        PageResult<T> result = new PageResult<>(data, total, pageNo, pageSize);
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    /**
     * Returns a successful page result with data, total, pageNo, pageSize, and requestId.
     *
     * @param data      list of records
     * @param total     total number of records
     * @param pageNo    page number
     * @param pageSize  page size
     * @param requestId request ID
     * @param <T>       data type
     * @return successful page result
     */
    public static <T> PageResult<T> successWithRequestId(T data, Long total, Integer pageNo, Integer pageSize, String requestId) {
        return new PageResult<>(data, total, pageNo, pageSize, requestId);
    }

    /**
     * Returns a successful page result with data, total, pageNo, pageSize, code, message, and requestId.
     *
     * @param data      list of records
     * @param total     total number of records
     * @param pageNo    page number
     * @param pageSize  page size
     * @param code      response code
     * @param message   response message
     * @param requestId request ID
     * @param <T>       data type
     * @return successful page result
     */
    public static <T> PageResult<T> successWithRequestId(T data, Long total, Integer pageNo, Integer pageSize, int code, String message, String requestId) {
        PageResult<T> result = new PageResult<>(data, total, pageNo, pageSize, requestId);
        result.setCode(code);
        result.setMessage(message);
        return result;
    }


    /**
     * Returns a successful page result with data, pageNo, and pageSize, total is 0.
     *
     * @param data     list of records
     * @param pageNo   page number
     * @param pageSize page size
     * @param <T>      data type
     * @return successful page result
     */
    public static <T> PageResult<T> success(T data, Integer pageNo, Integer pageSize) {
        return new PageResult<>(data, 0L, pageNo, pageSize);
    }

    /**
     * Returns a successful page result with data, pageNo, pageSize, total is 0, and requestId.
     *
     * @param data      list of records
     * @param pageNo    page number
     * @param pageSize  page size
     * @param requestId request ID
     * @param <T>       data type
     * @return successful page result
     */
    public static <T> PageResult<T> successWithRequestId(T data, Integer pageNo, Integer pageSize, String requestId) {
        return new PageResult<>(data, 0L, pageNo, pageSize, requestId);
    }


    /**
     * Returns a successful page result with data, total is 0, pageNo is 1, pageSize is 10.
     *
     * @param data list of records
     * @param <T>  data type
     * @return successful page result
     */
    public static <T> PageResult<T> success(T data) {
        return new PageResult<>(data, 0L, 1, 10);
    }

    /**
     * Returns a successful page result with data, total is 0, pageNo is 1, pageSize is 10, and requestId.
     *
     * @param data      list of records
     * @param requestId request ID
     * @param <T>       data type
     * @return successful page result
     */
    public static <T> PageResult<T> successWithRequestId(T data, String requestId) {
        return new PageResult<>(data, 0L, 1, 10, requestId);
    }

    /**
     * Returns a failed page result with data, code, and message.
     *
     * @param code    error code
     * @param message error message
     * @param <T>     data type
     * @return failed page result
     */
    public static <T> PageResult<T> fail(int code, String message) {
        PageResult<T> result = new PageResult<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(null);
        result.setSuccess(false);
        return result;
    }

    /**
     * Returns a failed page result with data, code, and message.
     *
     * @param data    response data
     * @param code    error code
     * @param message error message
     * @param <T>     data type
     * @return failed page result
     */
    public static <T> PageResult<T> fail(T data, int code, String message) {
        PageResult<T> result = new PageResult<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(data);
        result.setSuccess(false);
        return result;
    }

    /**
     * Returns a failed page result with data, code, message, and requestId.
     *
     * @param data      response data
     * @param code      error code
     * @param message   error message
     * @param requestId request ID
     * @param <T>       data type
     * @return failed page result
     */
    public static <T> PageResult<T> failWithRequestId(T data, int code, String message, String requestId) {
        PageResult<T> result = new PageResult<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(data);
        result.setSuccess(false);
        result.setRequestId(requestId);
        return result;
    }
}